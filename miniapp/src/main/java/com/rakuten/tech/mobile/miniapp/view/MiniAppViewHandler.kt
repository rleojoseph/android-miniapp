package com.rakuten.tech.mobile.miniapp.view

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.rakuten.tech.mobile.miniapp.MiniAppSdkConfig
import com.rakuten.tech.mobile.miniapp.MiniAppNetException
import com.rakuten.tech.mobile.miniapp.RequiredPermissionsNotGrantedException
import com.rakuten.tech.mobile.miniapp.MiniAppNotFoundException
import com.rakuten.tech.mobile.miniapp.MiniAppManifest
import com.rakuten.tech.mobile.miniapp.MiniAppDisplay
import com.rakuten.tech.mobile.miniapp.MiniAppInfo
import com.rakuten.tech.mobile.miniapp.MiniAppSdkException
import com.rakuten.tech.mobile.miniapp.MiniAppBundleNotFoundException
import com.rakuten.tech.mobile.miniapp.InvalidMiniAppInfoException
import com.rakuten.tech.mobile.miniapp.R
import com.rakuten.tech.mobile.miniapp.MiniAppDownloader
import com.rakuten.tech.mobile.miniapp.MiniAppInfoFetcher
import com.rakuten.tech.mobile.miniapp.analytics.MiniAppAnalytics
import com.rakuten.tech.mobile.miniapp.api.ApiClient
import com.rakuten.tech.mobile.miniapp.api.ApiClientRepository
import com.rakuten.tech.mobile.miniapp.api.ManifestApiCache
import com.rakuten.tech.mobile.miniapp.display.Displayer
import com.rakuten.tech.mobile.miniapp.js.iap.MiniAppIAPVerifier
import com.rakuten.tech.mobile.miniapp.js.MessageBridgeRatDispatcher
import com.rakuten.tech.mobile.miniapp.js.MiniAppSecureStorageDispatcher
import com.rakuten.tech.mobile.miniapp.js.hostenvironment.isValidLocale
import com.rakuten.tech.mobile.miniapp.permission.MiniAppCustomPermissionCache
import com.rakuten.tech.mobile.miniapp.signatureverifier.SignatureVerifier
import com.rakuten.tech.mobile.miniapp.storage.DownloadedManifestCache
import com.rakuten.tech.mobile.miniapp.storage.MiniAppStorage
import com.rakuten.tech.mobile.miniapp.storage.CachedManifest
import com.rakuten.tech.mobile.miniapp.storage.MiniAppStatus
import com.rakuten.tech.mobile.miniapp.storage.FileWriter
import com.rakuten.tech.mobile.miniapp.storage.verifier.CachedMiniAppVerifier
import com.rakuten.tech.mobile.miniapp.storage.verifier.MiniAppManifestVerifier
import java.util.Locale

@Suppress("LargeClass", "TooManyFunctions")
internal class MiniAppViewHandler(
    val context: Context,
    val config: MiniAppSdkConfig
) {
    constructor(context: Context, config: MiniAppSdkConfig, fromBundle: Boolean) : this(context, config) {
        if (fromBundle) {
            initializeMiniAppBundleConfig()
        } else {
            initializeDefaultMiniAppConfig()
        }
    }

    @VisibleForTesting
    internal lateinit var displayer: Displayer
    private lateinit var miniAppInfoFetcher: MiniAppInfoFetcher
    @VisibleForTesting
    internal lateinit var miniAppManifestVerifier: MiniAppManifestVerifier
    @VisibleForTesting
    internal lateinit var apiClientRepository: ApiClientRepository
    private lateinit var miniAppParameters: MiniAppParameters
    internal var enableH5Ads: Boolean = config.enableH5Ads
    internal lateinit var apiClient: ApiClient
    internal lateinit var miniAppAnalytics: MiniAppAnalytics
    @VisibleForTesting
    internal lateinit var miniAppDownloader: MiniAppDownloader
    internal lateinit var signatureVerifier: SignatureVerifier
    internal lateinit var ratDispatcher: MessageBridgeRatDispatcher
    internal lateinit var downloadedManifestCache: DownloadedManifestCache
    internal lateinit var secureStorageDispatcher: MiniAppSecureStorageDispatcher
    internal lateinit var miniAppCustomPermissionCache: MiniAppCustomPermissionCache
    internal lateinit var miniAppIAPVerifier: MiniAppIAPVerifier
    internal lateinit var miniAppStorage: MiniAppStorage
    internal lateinit var miniAppVerifier: CachedMiniAppVerifier
    internal lateinit var manifestApiCache: ManifestApiCache

    @VisibleForTesting
    internal fun initApiClient() = ApiClient(
        baseUrl = config.baseUrl,
        rasProjectId = config.rasProjectId,
        subscriptionKey = config.subscriptionKey,
        isPreviewMode = config.isPreviewMode,
        sslPublicKeyList = config.sslPinningPublicKeyList
    )
    @VisibleForTesting
    internal fun initMiniAppDownloader() = MiniAppDownloader(
        context = context,
        apiClient = apiClient,
        miniAppAnalytics = miniAppAnalytics,
        requireSignatureVerification = config.requireSignatureVerification,
        initStorage = { miniAppStorage },
        initStatus = { MiniAppStatus(context) },
        initVerifier = { miniAppVerifier },
        initManifestApiCache = { manifestApiCache },
        initSignatureVerifier = { signatureVerifier }
    )
    @VisibleForTesting
    internal fun initializeDefaultMiniAppConfig() {
        apiClient = initApiClient()
        displayer = Displayer(config.hostAppUserAgentInfo)
        miniAppInfoFetcher = MiniAppInfoFetcher(apiClient)
        downloadedManifestCache = DownloadedManifestCache(context)
        miniAppManifestVerifier = MiniAppManifestVerifier(context)
        miniAppCustomPermissionCache = MiniAppCustomPermissionCache(context)
        miniAppIAPVerifier = MiniAppIAPVerifier(context)
        miniAppStorage = MiniAppStorage(FileWriter(), context.filesDir)
        miniAppVerifier = CachedMiniAppVerifier(context)

        apiClientRepository = ApiClientRepository().apply {
            registerApiClient(config, apiClient)
        }
        signatureVerifier = SignatureVerifier.init(
            context = context,
            baseUrl = config.baseUrl + "keys/",
            subscriptionKey = config.subscriptionKey
        )!!
        miniAppAnalytics = MiniAppAnalytics(
            config.rasProjectId,
            config.miniAppAnalyticsConfigList
        )
        miniAppDownloader = initMiniAppDownloader()
        ratDispatcher = MessageBridgeRatDispatcher(miniAppAnalytics)
        secureStorageDispatcher = MiniAppSecureStorageDispatcher(
            context,
            config.maxStorageSizeLimitInBytes.toLong()
        )
        manifestApiCache = ManifestApiCache(context)
    }
    private fun initializeMiniAppBundleConfig() {
        displayer = Displayer(config.hostAppUserAgentInfo)
        downloadedManifestCache = DownloadedManifestCache(context)
        miniAppManifestVerifier = MiniAppManifestVerifier(context)
        miniAppStorage = MiniAppStorage(FileWriter(), context.filesDir)
        manifestApiCache = ManifestApiCache(context)
        miniAppCustomPermissionCache = MiniAppCustomPermissionCache(context)
    }

    @Suppress("LongMethod")
    suspend fun createMiniAppViewFromBundle(
        miniAppInfo: MiniAppInfo,
        config: MiniAppConfig,
        manifest: MiniAppManifest?,
        onComplete: (MiniAppDisplay?, MiniAppSdkException?) -> Unit,
    ) {
        if (miniAppStorage.isValidMiniAppInfo(miniAppInfo.id, miniAppInfo.version.versionId)) {
            if (manifest != null) {
                saveManifestForMiniAppBundle(
                    appId = miniAppInfo.id,
                    versionId = miniAppInfo.version.versionId,
                    languageCode = "",
                    manifest = manifest
                )
                downloadedManifestCache.storeDownloadedManifest(
                    miniAppId = miniAppInfo.id,
                    CachedManifest(miniAppInfo.id, manifest)
                )
            }
            if (miniAppStorage.isMiniAppAvailable(miniAppInfo.id, miniAppInfo.version.versionId)) {
                val versionPath = miniAppStorage.getBundleWritePath(
                    miniAppInfo.id,
                    miniAppInfo.version.versionId
                )
                val (basePath, miniAppInfo) = Pair(versionPath, miniAppInfo)
                onComplete(
                    displayer.createMiniAppDisplayForBundle(
                        basePath,
                        miniAppInfo,
                        config.miniAppMessageBridge,
                        config.miniAppNavigator,
                        miniAppCustomPermissionCache,
                        downloadedManifestCache,
                        config.queryParams
                    ), null
                )
                miniAppStorage.removeVersions(miniAppInfo.id, miniAppInfo.version.versionId)
            } else {
                onComplete(null, MiniAppBundleNotFoundException())
            }
        } else {
            onComplete(null, InvalidMiniAppInfoException())
        }
    }

    internal fun saveManifestForMiniAppBundle(
        appId: String,
        versionId: String,
        languageCode: String,
        manifest: MiniAppManifest
    ) {
        manifestApiCache.storeManifest(
            appId,
            versionId,
            languageCode,
            manifest
        )
    }

    suspend fun verifyManifest(appId: String, versionId: String, fromCache: Boolean = false) {
        val cachedManifest = downloadedManifestCache.readDownloadedManifest(appId)

        try {
            if (!fromCache)
                checkToDownloadManifest(appId, versionId, cachedManifest)
        } catch (e: MiniAppNetException) {
            Log.e(
                "MiniAppViewHandler",
                "Unable to retrieve latest manifest due to device being offline. " +
                        "Skipping manifest download.",
                e
            )
        }

        val manifestFile = downloadedManifestCache.getManifestFile(appId)
        if (cachedManifest != null && miniAppManifestVerifier.verify(appId, manifestFile)) {
            val customPermissions = miniAppCustomPermissionCache.readPermissions(appId)
            val manifestPermissions = downloadedManifestCache.getAllPermissions(customPermissions)
            miniAppCustomPermissionCache.removePermissionsNotMatching(appId, manifestPermissions)

            if (downloadedManifestCache.isRequiredPermissionDenied(customPermissions))
                throw RequiredPermissionsNotGrantedException(appId, versionId)
        } else {
            if (!fromCache) {
                checkToDownloadManifest(appId, versionId, cachedManifest)
            } else {
                throw MiniAppNotFoundException(MiniAppDownloader.MINIAPP_NOT_FOUND_OR_CORRUPTED)
            }
        }
    }

    suspend fun checkToDownloadManifest(
        appId: String,
        versionId: String,
        cachedManifest: CachedManifest?
    ) {
        var locale = context.getString(R.string.miniapp_sdk_android_locale)
        locale = Locale.forLanguageTag(locale).language
        if (!locale.isValidLocale()) locale = ""
        val apiManifest = getMiniAppManifest(appId, versionId, locale)
        val isDifferentVersion = cachedManifest?.versionId != versionId
        val isSameVerDiffApp = !isManifestEqual(apiManifest, cachedManifest?.miniAppManifest)
        if (isDifferentVersion || isSameVerDiffApp) {
            val storableManifest = CachedManifest(versionId, apiManifest)
            downloadedManifestCache.storeDownloadedManifest(appId, storableManifest)
            val manifestFile = downloadedManifestCache.getManifestFile(appId)
            miniAppManifestVerifier.storeHashAsync(appId, manifestFile)
        }
    }

    suspend fun getMiniAppManifest(
        appId: String,
        versionId: String,
        languageCode: String
    ): MiniAppManifest = miniAppDownloader.fetchMiniAppManifest(appId, versionId, languageCode)

    fun isManifestEqual(
        apiManifest: MiniAppManifest?,
        downloadedManifest: MiniAppManifest?
    ): Boolean {
        if (apiManifest != null && downloadedManifest != null) {
            val changedRequiredPermissions =
                (apiManifest.requiredPermissions + downloadedManifest.requiredPermissions).groupBy { it.first.type }
                    .filter { it.value.size == 1 }
                    .flatMap { it.value }

            val changedOptionalPermissions =
                (apiManifest.optionalPermissions + downloadedManifest.optionalPermissions).groupBy { it.first.type }
                    .filter { it.value.size == 1 }
                    .flatMap { it.value }

            return changedRequiredPermissions.isEmpty() && changedOptionalPermissions.isEmpty() &&
                    apiManifest.customMetaData == downloadedManifest.customMetaData
        }
        return false
    }

    suspend fun createMiniAppView(
        miniAppId: String,
        config: MiniAppConfig,
        fromCache: Boolean = false
    ): MiniAppDisplay {
        val (basePath, miniAppInfo) = if (!fromCache) {
            miniAppDownloader.getMiniApp(miniAppId)
        } else {
            miniAppDownloader.getCachedMiniApp(miniAppId)
        }
        verifyManifest(miniAppInfo.id, miniAppInfo.version.versionId, fromCache)
        config.miniAppMessageBridge.updateApiClient(apiClient)

        return displayer.createMiniAppDisplay(
            basePath,
            miniAppInfo,
            config.miniAppMessageBridge,
            config.miniAppNavigator,
            config.miniAppFileChooser,
            miniAppCustomPermissionCache,
            downloadedManifestCache,
            config.queryParams,
            miniAppAnalytics,
            ratDispatcher,
            secureStorageDispatcher,
            enableH5Ads,
            miniAppIAPVerifier
        )
    }

    suspend fun createMiniAppView(
        miniAppInfo: MiniAppInfo,
        config: MiniAppConfig,
        fromCache: Boolean = false
    ): MiniAppDisplay {
        val (basePath, miniAppInfo) = if (!fromCache) {
            miniAppDownloader.getMiniApp(miniAppInfo)
        } else {
            miniAppDownloader.getCachedMiniApp(miniAppInfo)
        }
        config.miniAppMessageBridge.updateApiClient(apiClient)
        verifyManifest(miniAppInfo.id, miniAppInfo.version.versionId, fromCache)
        return displayer.createMiniAppDisplay(
            basePath,
            miniAppInfo,
            config.miniAppMessageBridge,
            config.miniAppNavigator,
            config.miniAppFileChooser,
            miniAppCustomPermissionCache,
            downloadedManifestCache,
            config.queryParams,
            miniAppAnalytics,
            ratDispatcher,
            secureStorageDispatcher,
            enableH5Ads,
            miniAppIAPVerifier
        )
    }

    suspend fun createMiniAppViewWithUrl(
        miniAppUrl: String,
        config: MiniAppConfig
    ): MiniAppDisplay {
        miniAppDownloader.validateHttpAppUrl(miniAppUrl)
        return displayer.createMiniAppDisplay(
            miniAppUrl,
            config.miniAppMessageBridge,
            config.miniAppNavigator,
            config.miniAppFileChooser,
            miniAppCustomPermissionCache,
            downloadedManifestCache,
            config.queryParams,
            miniAppAnalytics,
            ratDispatcher,
            secureStorageDispatcher,
            enableH5Ads,
            miniAppIAPVerifier
        )
    }
}
