package com.didi.dimina.core

import android.content.Context
import com.didi.dimina.api.permission.MiniProgramPermissionStore
import com.didi.dimina.api.permission.PermLog
import com.didi.dimina.api.storage.StorageApi
import com.didi.dimina.bean.MiniProgram
import com.didi.dimina.common.AtomicZipInstaller
import com.didi.dimina.common.LogUtils
import com.didi.dimina.common.VersionUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object RemoteUpdateManager {
    private const val TAG = "RemoteUpdateManager"
    internal const val EVENT_NO_UPDATE = "noupdate"
    internal const val EVENT_UPDATING = "updating"
    internal const val EVENT_UPDATE_READY = "updateready"
    internal const val EVENT_UPDATE_FAILED = "updatefail"

    private val requiredPackagePaths = listOf(
        "config.json",
        "main/app-config.json",
        "main/logic.js",
    )
    private val appLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val operationGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val uninstallingApps = ConcurrentHashMap.newKeySet<String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate(
        context: Context,
        miniProgram: MiniProgram,
        notify: (String) -> Unit,
        operationToken: Long = operationToken(miniProgram.appId),
    ) {
        if (!isOperationActive(miniProgram.appId, operationToken)) return
        lockFor(miniProgram.appId).withLock {
            if (!isOperationActive(miniProgram.appId, operationToken)) return@withLock
            checkForUpdateLocked(context, miniProgram, notify, operationToken)
        }
    }

    /**
     * Installs the package described by [MiniProgram.updateManifestUrl] directly
     * into the active app directory.
     *
     * This path is only used when no runnable local package exists yet. Unlike
     * the normal update flow, the package must be activated before the service
     * and render runtimes can start.
     */
    @Throws(IOException::class)
    fun installInitialPackage(context: Context, miniProgram: MiniProgram): MiniProgram {
        val operationToken = operationToken(miniProgram.appId)
        return lockFor(miniProgram.appId).withLock {
            ensureOperationActive(miniProgram.appId, operationToken)
            val manifestUrl = miniProgram.updateManifestUrl.trim()
            if (manifestUrl.isEmpty()) {
                throw IOException(
                    "mini program ${miniProgram.appId} has no local package or updateManifestUrl"
                )
            }

            val manifest = fetchManifest(manifestUrl)
            ensureOperationActive(miniProgram.appId, operationToken)
            validateManifestAppId(manifest, miniProgram.appId)

            val zipFile = downloadPackage(context, manifest)
            if (!isOperationActive(miniProgram.appId, operationToken)) {
                zipFile.delete()
                throw UpdateOperationCancelledException()
            }
            installActivePackage(context, manifest, zipFile)

            miniProgram.copy(
                name = miniProgram.name.ifBlank { manifest.name },
                path = miniProgram.path ?: manifest.path,
                versionCode = manifest.versionCode,
                versionName = manifest.versionName,
            )
        }
    }

    /**
     * Promotes the downloaded package to the active directory. The current
     * runtime continues to use the old directory until this method is called.
     */
    fun activatePendingUpdate(context: Context, appId: String): Boolean {
        val operationToken = operationToken(appId)
        if (!isOperationActive(appId, operationToken)) return false
        return lockFor(appId).withLock {
            if (!isOperationActive(appId, operationToken)) return@withLock false
            try {
                val pendingPackage = readPendingPackage(context, appId) ?: return@withLock false
                val currentVersion = VersionUtils.getAppVersion(appId)
                if (pendingPackage.versionCode <= currentVersion) {
                    pendingPackage.directory.deleteRecursively()
                    return@withLock false
                }

                val activeDir = appDirectory(jsAppRoot(context), appId)
                AtomicZipInstaller.replaceDirectory(
                    sourceDir = pendingPackage.directory,
                    targetDir = activeDir,
                    onInstalled = {
                        VersionUtils.setAppVersion(appId, pendingPackage.versionCode)
                    },
                )
                true
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to activate pending update for $appId: ${e.message}")
                false
            }
        }
    }

    /** Invalidates queued update checks before the host starts runtime teardown. */
    fun getAppVersionInfo(context: Context, appId: String): JSONObject? {
        requireValidAppId(appId)
        return lockFor(appId).withLock {
            runCatching {
                val directory = appDirectory(jsAppRoot(context), appId)
                check(File(directory, "main/app-config.json").isFile && File(directory, "main/logic.js").isFile)
                val configFile = File(directory, "config.json")
                val json = if (configFile.isFile) configFile.readText() else
                    context.assets.open("jsapp/$appId/config.json").bufferedReader().use { it.readText() }
                JSONObject(json).also {
                    check(it.getString("appId") == appId && it.getInt("versionCode") >= 0)
                }
            }.getOrNull()
        }
    }

    /** autoSync 静默同步的结果(outcome: installed / updated / noupdate / failed)。 */
    data class SyncResult(val outcome: String, val versionCode: Int, val versionName: String)

    /**
     * autoSync 静默同步(autoSync 扩展):后台把 [miniProgram.updateManifestUrl]
     * 描述的包装进本机,全程无界面、**无确认弹窗**——确认语义由服务端
     * bundle.json 的 autoSync 字段表达,调用方是宿主的静默同步链路。
     *
     *   没有可运行包 → [installInitialPackage](直接进 active,"installed");
     *   有可运行包且 manifest 版本更新 → 下载校验进 pending 并立即激活("updated");
     *   已最新 / manifest 缺失 → "noupdate"。
     *
     * 抛出 IO 异常(网络/校验失败);里程碑(installed/applied)由调用方广播。
     */
    fun syncPackage(context: Context, miniProgram: MiniProgram): SyncResult {
        val manifestUrl = miniProgram.updateManifestUrl.trim()
        if (manifestUrl.isEmpty()) return SyncResult("noupdate", 0, "")
        return lockFor(miniProgram.appId).withLock {
            val hasRunnable = try {
                File(jsAppRoot(context), "${miniProgram.appId}/main/logic.js").isFile
            } catch (_: Exception) {
                false
            }
            if (!hasRunnable) {
                // 静默首装:与 open 的无底包首装同一入口(active 目录)。
                val installed = installInitialPackage(context, miniProgram)
                return@withLock SyncResult("installed", installed.versionCode, installed.versionName)
            }
            val manifest = fetchManifest(manifestUrl)
            validateManifestAppId(manifest, miniProgram.appId)
            val currentVersion = VersionUtils.getAppVersion(miniProgram.appId)
            if (manifest.versionCode <= currentVersion) {
                deletePendingPackage(context, miniProgram.appId)
                return@withLock SyncResult("noupdate", currentVersion, "")
            }
            val zipFile = downloadPackage(context, manifest)
            try {
                installPendingPackage(context, manifest, zipFile)
            } finally {
                zipFile.delete()
            }
            // 立即激活:ReentrantLock 同线程可重入,activatePendingUpdate 的
            // operation token 校验此时必然有效(无卸载竞争)。
            if (!activatePendingUpdate(context, miniProgram.appId)) {
                return@withLock SyncResult("failed", manifest.versionCode, manifest.versionName)
            }
            SyncResult("updated", manifest.versionCode, manifest.versionName)
        }
    }

    internal fun isPackageOperationInProgress(appId: String): Boolean = uninstallingApps.contains(appId)

    // ------------------------------------------------------------------
    // 权限声明可达性（契约 §3，2026-11-14 真机修复）
    // ------------------------------------------------------------------

    private const val PERMISSION_REFRESH_THROTTLE_MS = 30_000L

    private val permissionRefreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dimina-perm-refresh").apply { isDaemon = true }
    }
    private val permissionRefreshAt = ConcurrentHashMap<String, Long>()

    /**
     * 打开即刷新声明：正常打开「已有可运行包」的小程序不经过 fetchManifest（只有首装/
     * 检查更新/autoSync 才会），multi-task 恢复态与旧容器时代安装的存量包声明面会永远
     * 停在安装时代（2026-11-14 214 真机教训：audio-test v2 声明不可达 → 守门全静默）。
     *
     * 这里对已装包主动拉一次 manifest.json——[fetchManifest] 内部会喂
     * `MiniProgramPermissionStore.onManifest`，账本侧再把缺段 config.json 补写。
     * 30s 按 appId 节流（activity 重建/快速重开不重复打网）。fire-and-forget：
     * 失败只落日志，绝不阻塞/拖垮打开链路。
     */
    fun refreshPermissionDeclarations(context: Context, miniProgram: MiniProgram) {
        val manifestUrl = miniProgram.updateManifestUrl.trim()
        if (manifestUrl.isEmpty()) {
            PermLog.i("refresh", "${miniProgram.appId} skip: no updateManifestUrl")
            return
        }
        val now = System.currentTimeMillis()
        val last = permissionRefreshAt[miniProgram.appId] ?: 0L
        if (now - last < PERMISSION_REFRESH_THROTTLE_MS) return
        permissionRefreshAt[miniProgram.appId] = now
        permissionRefreshExecutor.execute {
            try {
                val manifest = fetchManifest(manifestUrl)
                validateManifestAppId(manifest, miniProgram.appId)
                PermLog.i("refresh", "${miniProgram.appId} ok v${manifest.versionCode}")
            } catch (e: Exception) {
                PermLog.i("refresh", "${miniProgram.appId} failed: ${e.message}")
            }
        }
    }

    internal fun installLocalPackage(context: Context, appId: String, packagePath: String): JSONObject {
        requireValidAppId(appId)
        check(uninstallingApps.contains(appId)) { "package operation must be reserved first" }
        return lockFor(appId).withLock {
            val archive = File(packagePath)
            require(archive.isFile) { "local ZIP package does not exist" }
            val target = appDirectory(jsAppRoot(context), appId)
            var installedConfig: JSONObject? = null
            check(AtomicZipInstaller.install(
                inputProvider = { archive.inputStream() },
                targetDir = target,
                requiredPaths = requiredPackagePaths,
                afterExtract = { staging ->
                    require(validatePackage(staging, appId) >= 0) { "invalid versionCode" }
                    installedConfig = JSONObject(File(staging, "config.json").readText()).put("hostManaged", true)
                    val version = installedConfig!!.opt("versionCode")
                    require(version is Number && version.toDouble() >= 0 &&
                        version.toDouble() == version.toInt().toDouble()) { "versionCode must be a non-negative integer" }
                    File(staging, "config.json").writeText(installedConfig.toString())
                },
            )) { "failed to extract or validate local package" }
            VersionUtils.setAppVersion(appId, installedConfig!!.getInt("versionCode"))
            deletePendingPackage(context, appId)
            installedConfig!!
        }
    }

    internal fun beginUninstall(appId: String) {
        requireValidAppId(appId)
        check(uninstallingApps.add(appId)) { "mini program $appId is already being uninstalled" }
        generationFor(appId).incrementAndGet()
    }

    internal fun endUninstall(appId: String) {
        uninstallingApps.remove(appId)
    }

    /** Removes package/update artifacts while holding the same lock as install and activation. */
    internal fun uninstallPackage(context: Context, appId: String, clearUserData: Boolean) {
        requireValidAppId(appId)
        check(uninstallingApps.contains(appId)) { "beginUninstall must be called first" }
        lockFor(appId).withLock {
            MiniProgramPackageCleaner.uninstall(
                filesDir = context.filesDir,
                cacheDir = context.cacheDir,
                appId = appId,
                clearUserData = clearUserData,
            )
            VersionUtils.removeAppVersion(appId)
            // per-appid 授权态随卸载清空（授权是给「这个小程序」的，重装重新弹）；
            // 与 StorageApi.clearAllStorage 的业务数据清算是两个独立动作。
            MiniProgramPermissionStore.clear(appId)
            if (clearUserData) {
                StorageApi.clearAllStorage(appId)
            }
        }
    }

    private fun checkForUpdateLocked(
        context: Context,
        miniProgram: MiniProgram,
        notify: (String) -> Unit,
        operationToken: Long,
    ) {
        val manifestUrl = miniProgram.updateManifestUrl.trim()
        if (manifestUrl.isEmpty()) {
            notify(EVENT_NO_UPDATE)
            return
        }

        var updateAnnounced = false
        try {
            val manifest = fetchManifest(manifestUrl)
            ensureOperationActive(miniProgram.appId, operationToken)
            validateManifestAppId(manifest, miniProgram.appId)

            val currentVersion = VersionUtils.getAppVersion(miniProgram.appId)
            if (manifest.versionCode <= currentVersion) {
                deletePendingPackage(context, miniProgram.appId)
                notify(EVENT_NO_UPDATE)
                return
            }

            updateAnnounced = true
            notify(EVENT_UPDATING)

            val pendingPackage = readPendingPackage(context, miniProgram.appId)
            if (pendingPackage != null && pendingPackage.versionCode >= manifest.versionCode) {
                notify(EVENT_UPDATE_READY)
                return
            }

            val zipFile = downloadPackage(context, manifest)
            if (!isOperationActive(miniProgram.appId, operationToken)) {
                zipFile.delete()
                return
            }
            installPendingPackage(context, manifest, zipFile)
            notify(EVENT_UPDATE_READY)
        } catch (_: UpdateOperationCancelledException) {
            return
        } catch (e: Exception) {
            LogUtils.e(TAG, "Remote update failed: ${e.message}")
            notify(if (updateAnnounced) EVENT_UPDATE_FAILED else EVENT_NO_UPDATE)
        }
    }

    private fun installActivePackage(
        context: Context,
        manifest: RemoteUpdateManifest,
        zipFile: File,
    ) {
        try {
            if (!manifest.sha256.isNullOrBlank()) {
                verifySha256(zipFile, manifest.sha256)
            }
            val activeDir = appDirectory(jsAppRoot(context), manifest.appId)
            val installed = AtomicZipInstaller.install(
                inputProvider = { zipFile.inputStream() },
                targetDir = activeDir,
                requiredPaths = requiredPackagePaths,
                afterExtract = { packageDir ->
                    writeConfig(manifest, File(packageDir, "config.json"))
                },
            )
            if (!installed) {
                throw IOException("failed to extract or validate initial package")
            }
            validatePackage(activeDir, manifest.appId)
            VersionUtils.setAppVersion(manifest.appId, manifest.versionCode)
        } finally {
            zipFile.delete()
        }
    }

    private fun fetchManifest(manifestUrl: String): RemoteUpdateManifest {
        val request = Request.Builder().url(manifestUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("manifest request failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val payload = json.optJSONObject("data") ?: json
            val manifest = RemoteUpdateManifest.fromJson(payload)
            // 权限声明通道（契约 §3）：wanqi /apps/<id>/manifest.json 的 permissions 段
            // （{scopes:[{key,title,usage,mode}],network:{domains}}）喂给容器守门/弹窗。
            // 所有 fetch 路径（首装/检查更新/autoSync）都过这里，声明随最新 manifest 刷新。
            MiniProgramPermissionStore.onManifest(manifest.appId, manifest.permissions)
            return manifest
        }
    }

    private fun downloadPackage(context: Context, manifest: RemoteUpdateManifest): File {
        val updateDir = File(context.cacheDir, "dimina-updates").apply { mkdirs() }
        val targetFile = File(updateDir, "${manifest.appId}-${manifest.versionCode}.zip")
        val request = Request.Builder().url(manifest.packageUrl).get().build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("package download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("package response body is empty")
            FileOutputStream(targetFile).use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }

        return targetFile
    }

    private fun installPendingPackage(
        context: Context,
        manifest: RemoteUpdateManifest,
        zipFile: File,
    ) {
        val pendingDir = pendingAppDirectory(context, manifest.appId)
        try {
            if (!manifest.sha256.isNullOrBlank()) {
                verifySha256(zipFile, manifest.sha256)
            }
            val installed = AtomicZipInstaller.install(
                inputProvider = { zipFile.inputStream() },
                targetDir = pendingDir,
                requiredPaths = requiredPackagePaths,
                afterExtract = { packageDir ->
                    writeConfig(manifest, File(packageDir, "config.json"))
                },
            )
            if (!installed) {
                throw IOException("failed to extract or validate update package")
            }
            validatePackage(pendingDir, manifest.appId)
        } finally {
            zipFile.delete()
        }
    }

    private fun readPendingPackage(context: Context, appId: String): PendingPackage? {
        val pendingDir = pendingAppDirectory(context, appId)
        if (!pendingDir.exists()) {
            return null
        }

        return try {
            val versionCode = validatePackage(pendingDir, appId)
            PendingPackage(pendingDir, versionCode)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Discarding invalid pending package for $appId: ${e.message}")
            pendingDir.deleteRecursively()
            null
        }
    }

    /** 已就绪 pending 包的版本信息(磁盘事实;无 pending 或包损坏时为 null)。 */
    internal class PendingUpdateInfo(val versionCode: Int, val versionName: String)

    /**
     * 探测已就绪的 pending 新包,供容器在冷启动时静默激活(契约 §6.4:强制自动升级,不弹窗)。
     * 只读不删:无效 pending 由 [readPendingPackage] 的既有清理逻辑处理。
     */
    internal fun peekPendingUpdate(context: Context, appId: String): PendingUpdateInfo? {
        requireValidAppId(appId)
        val pending = readPendingPackage(context, appId) ?: return null
        return try {
            val config = JSONObject(File(pending.directory, "config.json").readText())
            PendingUpdateInfo(pending.versionCode, config.optString("versionName"))
        } catch (e: Exception) {
            PendingUpdateInfo(pending.versionCode, "")
        }
    }

    private fun deletePendingPackage(context: Context, appId: String) {
        try {
            pendingAppDirectory(context, appId).deleteRecursively()
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to clean pending package for $appId: ${e.message}")
        }
    }

    private fun validatePackage(packageDir: File, expectedAppId: String): Int {
        val missingPath = requiredPackagePaths.firstOrNull { path ->
            !File(packageDir, path).isFile
        }
        if (missingPath != null) {
            throw IOException("package missing required file: $missingPath")
        }

        val config = JSONObject(File(packageDir, "config.json").readText())
        val appId = config.getString("appId")
        if (appId != expectedAppId) {
            throw IOException("package appId $appId does not match $expectedAppId")
        }
        return config.getInt("versionCode")
    }

    private fun validateManifestAppId(
        manifest: RemoteUpdateManifest,
        expectedAppId: String,
    ) {
        if (manifest.appId != expectedAppId) {
            throw IOException("manifest appId ${manifest.appId} does not match $expectedAppId")
        }
    }

    private fun writeConfig(manifest: RemoteUpdateManifest, configFile: File) {
        configFile.parentFile?.mkdirs()
        val config = JSONObject().apply {
            put("appId", manifest.appId)
            put("name", manifest.name)
            put("path", manifest.path)
            put("versionCode", manifest.versionCode)
            put("versionName", manifest.versionName)
            // 权限声明随版本落盘（契约 §3）：声明属于这个包版本，离线首启/进程重建时
            // 守门与弹窗从 config.json 读（MiniProgramPermissionStore.DeclarationSource）。
            manifest.permissions?.let { put("permissions", it) }
        }
        configFile.writeText(config.toString())
    }

    private fun pendingAppDirectory(context: Context, appId: String): File {
        val pendingRoot = File(jsAppRoot(context), ".pending").apply { mkdirs() }
        return appDirectory(pendingRoot, appId)
    }

    private fun jsAppRoot(context: Context): File =
        File(context.filesDir, "jsapp").apply { mkdirs() }

    private fun appDirectory(root: File, appId: String): File {
        val canonicalRoot = root.canonicalFile
        val directory = File(canonicalRoot, appId).canonicalFile
        if (directory.parentFile != canonicalRoot) {
            throw IOException("invalid appId path: $appId")
        }
        return directory
    }

    private fun verifySha256(file: File, expectedSha256: String) {
        val actualSha256 = sha256(file)
        if (!actualSha256.equals(expectedSha256.lowercase(Locale.US), ignoreCase = true)) {
            throw IOException("package sha256 mismatch")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var length: Int
            while (input.read(buffer).also { length = it } > 0) {
                digest.update(buffer, 0, length)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun lockFor(appId: String): ReentrantLock =
        appLocks.computeIfAbsent(appId) { ReentrantLock() }

    internal fun operationToken(appId: String): Long = generationFor(appId).get()

    private fun generationFor(appId: String): AtomicLong =
        operationGenerations.computeIfAbsent(appId) { AtomicLong(0) }

    private fun isOperationActive(appId: String, token: Long): Boolean =
        !uninstallingApps.contains(appId) && generationFor(appId).get() == token

    private fun ensureOperationActive(appId: String, token: Long) {
        if (!isOperationActive(appId, token)) {
            throw UpdateOperationCancelledException()
        }
    }

    private fun requireValidAppId(appId: String) {
        val reservedAppIds = setOf(".pending")
        require(
            appId.isNotBlank() && appId != "." && appId != ".." &&
                !appId.contains('/') && !appId.contains('\\') && !appId.contains('\u0000') &&
                appId !in reservedAppIds
        ) { "invalid appId" }
    }

    private class UpdateOperationCancelledException : IOException("update operation cancelled")

    private data class PendingPackage(
        val directory: File,
        val versionCode: Int,
    )

    private data class RemoteUpdateManifest(
        val appId: String,
        val name: String,
        val path: String,
        val versionCode: Int,
        val versionName: String,
        val packageUrl: String,
        val sha256: String?,
        /** manifest.json 的 permissions 段（{scopes,network}）；老包无该段=null。 */
        val permissions: JSONObject?,
    ) {
        companion object {
            fun fromJson(json: JSONObject): RemoteUpdateManifest {
                val packageUrl = json.optString("packageUrl")
                    .ifBlank { json.optString("downloadUrl") }
                    .ifBlank { json.optString("url") }

                if (packageUrl.isBlank()) {
                    throw IOException("manifest missing packageUrl")
                }

                return RemoteUpdateManifest(
                    appId = json.getString("appId"),
                    name = json.optString("name"),
                    path = json.getString("path"),
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName"),
                    packageUrl = packageUrl,
                    sha256 = json.optString("sha256").ifBlank { null },
                    permissions = json.optJSONObject("permissions"),
                )
            }
        }
    }
}
