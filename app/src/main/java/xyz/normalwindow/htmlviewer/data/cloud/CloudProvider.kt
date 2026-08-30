package xyz.normalwindow.htmlviewer.data.cloud

import java.io.File

/**
 * 云盘 Provider 抽象:百度网盘 / WebDAV 各实现一份,后续其他平台(如 SFTP/SMB)按此接入。
 *
 * 所有路径均使用相对远端根目录的相对路径(以 / 分隔,空串表示根目录),
 * 各实现自行拼接平台前缀(百度为 /apps/<应用名>,WebDAV 为用户配置的远端目录)。
 * 全部方法为挂起函数并返回 Result,IO 统一切到 Dispatchers.IO。
 */
interface CloudProvider {
    val type: CloudProviderType

    /** 鉴权检查(必要时刷新令牌);失败抛 CloudException,由调用方包装 */
    suspend fun checkAuth()

    /**
     * 列目录(不递归)。
     * @param dir 相对远端根目录的目录路径,空串 = 根目录
     */
    suspend fun list(dir: String): Result<List<CloudFile>>

    /** 下载远端文件到本地目标路径(临时文件 + 原子重命名,失败不破坏已有文件) */
    suspend fun download(relPath: String, dest: File): Result<Unit>

    /**
     * 上传本地文件到远端(自动逐级创建父目录)。
     * @return 上传后的远端修改时间(epoch 秒;平台未返回时为当前时间)
     */
    suspend fun upload(relPath: String, src: File): Result<Long>

    /** 逐级创建目录(已存在视为成功) */
    suspend fun mkdirs(relPath: String): Result<Unit>

    /** 删除远端文件/目录(目录递归;不存在视为成功) */
    suspend fun delete(relPath: String): Result<Unit>

    /** 读取单个文件元数据(测试连接/刷新单个条目;目录返回 isDir=true 条目) */
    suspend fun meta(relPath: String): Result<CloudFile?>
}
