package xyz.normalwindow.htmlviewer.ui.editor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.normalwindow.htmlviewer.data.file.FileRepository
import xyz.normalwindow.htmlviewer.data.file.TextEncoding
import xyz.normalwindow.htmlviewer.data.settings.EngineType
import xyz.normalwindow.htmlviewer.data.settings.SettingsRepository
import java.io.File
import javax.inject.Inject

/** 编辑器 Snackbar 语义 */
enum class EditorSnack {
    SAVED, SAVING_FAILED, LOAD_FAILED, CONVERTED_UTF8, MISSING_FILE, FORMATTED, FORMAT_FAILED
}

sealed interface EditorEvent {
    data class Snackbar(val kind: EditorSnack) : EditorEvent
    /** 请求 UI 层从编辑器 WebView 拉取内容 */
    data object RequestSave : EditorEvent
}

data class EditorUiState(
    val path: String = "",
    val fileName: String = "",
    /** 初始文件内容(WebView 就绪后注入) */
    val editorContent: String = "",
    val encoding: String = TextEncoding.UTF_8,
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val cursorLine: Int = 1,
    val cursorCol: Int = 1,
    val autoSave: Boolean = true,
    val fontSize: Float = 14f,
    val tabSize: Int = 4,
    val wrap: Boolean = false,
    /** 右侧自定义滚动条(vscode 风格)显示开关 */
    val scrollbar: Boolean = true,
    val engine: EngineType = EngineType.WEBVIEW
) {
    val readyForInit: Boolean get() = !loading && path.isNotEmpty()
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var autoSaveJob: Job? = null

    fun load(path: String, name: String) {
        viewModelScope.launch {
            val file = File(path)
            if (!file.isFile) {
                _events.send(EditorEvent.Snackbar(EditorSnack.MISSING_FILE))
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val decoded = fileRepository.readText(file).getOrNull()
            val content = decoded?.content ?: ""
            val lines = if (content.isEmpty()) 1 else content.count { it == '\n' } + 1
            val prefs = settingsRepository.preferences.first()
            _state.update {
                it.copy(
                    path = path,
                    fileName = name.ifBlank { file.name },
                    editorContent = content,
                    encoding = decoded?.encoding ?: TextEncoding.UTF_8,
                    loading = false,
                    autoSave = prefs.editorAutoSave,
                    fontSize = prefs.editorFontSize,
                    tabSize = prefs.editorTabSize,
                    wrap = prefs.editorWrap,
                    scrollbar = prefs.editorScrollbar,
                    engine = prefs.defaultEngine
                )
            }
            fileRepository.touchOpened(path, decoded?.encoding, lines, content.length)
        }
    }

    fun onEditorReady() {
        _state.update { it.copy() } // 通知 UI 初始化已完成(由 UI 侧处理)
    }

    /** 编辑器内容填充完成:清除分片加载产生的脏标记(打开文件不应触发未保存提示) */
    fun onContentLoaded() {
        _state.update { it.copy(dirty = false) }
    }

    fun onEditorChanged() {
        _state.update { it.copy(dirty = true) }
        if (_state.value.autoSave) scheduleAutoSave()
    }

    fun onCursorChanged(line: Int, col: Int) {
        _state.update { it.copy(cursorLine = line, cursorCol = col) }
    }

    fun setAutoSave(enabled: Boolean) {
        _state.update { it.copy(autoSave = enabled) }
        viewModelScope.launch { settingsRepository.setEditorAutoSave(enabled) }
        if (enabled && _state.value.dirty) scheduleAutoSave()
    }

    /** 右侧自定义滚动条显示开关(持久化,UI 侧同步到 JS) */
    fun setScrollbar(enabled: Boolean) {
        _state.update { it.copy(scrollbar = enabled) }
        viewModelScope.launch { settingsRepository.setEditorScrollbar(enabled) }
    }

    fun requestManualSave() {
        _events.trySend(EditorEvent.RequestSave)
    }

    /** UI 层拉取内容后调用;onDone 在写盘完成(成功或失败)后于主线程回调 */
    fun saveWithContent(content: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val s = _state.value
            val result = fileRepository.writeText(File(s.path), content, s.encoding)
            result.onSuccess {
                _state.update { it.copy(dirty = false) }
                _events.send(EditorEvent.Snackbar(EditorSnack.SAVED))
            }.onFailure {
                _events.send(EditorEvent.Snackbar(EditorSnack.SAVING_FAILED))
            }
            onDone()
        }
    }

    /** 转存为 UTF-8(编码切换后保存) */
    fun convertToUtf8(content: String) {
        viewModelScope.launch {
            val s = _state.value
            fileRepository.writeText(File(s.path), content, TextEncoding.UTF_8)
                .onSuccess {
                    _state.update { it.copy(encoding = TextEncoding.UTF_8, dirty = false) }
                    _events.send(EditorEvent.Snackbar(EditorSnack.CONVERTED_UTF8))
                }.onFailure {
                    _events.send(EditorEvent.Snackbar(EditorSnack.SAVING_FAILED))
                }
        }
    }

    /** 一键整理格式结果:result=="true" 成功,否则为 JS 侧错误信息 */
    fun onFormatResult(ok: Boolean, error: String) {
        if (ok) {
            _state.update { it.copy(dirty = true) }
            _events.trySend(EditorEvent.Snackbar(EditorSnack.FORMATTED))
        } else {
            _events.trySend(EditorEvent.Snackbar(EditorSnack.FORMAT_FAILED))
        }
    }

    /** 分块保存内容传输不完整:拒绝覆盖文件并提示 */
    fun onSaveChunkError() {
        _events.trySend(EditorEvent.Snackbar(EditorSnack.SAVING_FAILED))
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            _events.send(EditorEvent.RequestSave)
        }
    }

    private companion object {
        const val AUTO_SAVE_DELAY_MS = 900L
    }
}
