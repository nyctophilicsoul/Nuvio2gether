package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.plugins.currentEpochMillis
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object CloudStreamRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("CloudStreamRepo")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(CloudStreamUiState())
    actual val uiState: StateFlow<CloudStreamUiState> = _uiState.asStateFlow()

    private val profileLock = SynchronizedObject()
    private var profileGeneration = 0L
    private var initialized = false
    private var currentProfileId = 1
    private val refreshJobs = mutableMapOf<String, Job>()

    private fun profileGenerationSnapshot(): Long = synchronized(profileLock) { profileGeneration }

    private fun isProfileGenerationCurrent(expectedGeneration: Long): Boolean =
        synchronized(profileLock) { profileGeneration == expectedGeneration }

    /**
     * All profile-scoped commits are serialized with profile switches. Network and
     * package downloads happen outside this lock; only the short state/storage commit
     * is guarded, so an old request cannot write its result into the newly active profile.
     */
    private fun commitForProfile(expectedGeneration: Long, action: () -> Unit): Boolean =
        synchronized(profileLock) {
            if (profileGeneration != expectedGeneration) {
                false
            } else {
                action()
                true
            }
        }

    actual fun initialize() {
        val jobsToCancel = mutableListOf<Job>()
        synchronized(profileLock) {
            val profileId = ProfileRepository.activeProfileId.coerceAtLeast(1)
            if (!initialized || currentProfileId != profileId) {
                if (initialized && currentProfileId != profileId) {
                    profileGeneration += 1
                    jobsToCancel += refreshJobs.values
                    refreshJobs.clear()
                    CloudStreamPlatformRuntime.clear()
                }
                currentProfileId = profileId
                CloudStreamPlatformStorage.setActiveProfile(profileId)
                initialized = true
                _uiState.value = restoreState(profileId)
            }
        }
        jobsToCancel.forEach { it.cancel() }
    }

    actual fun onProfileChanged(profileId: Int) {
        val jobsToCancel = synchronized(profileLock) {
            profileGeneration += 1
            refreshJobs.values.toList().also {
                refreshJobs.clear()
                CloudStreamPlatformRuntime.clear()
                currentProfileId = profileId.coerceAtLeast(1)
                CloudStreamPlatformStorage.setActiveProfile(currentProfileId)
                initialized = false
                _uiState.value = CloudStreamUiState()
            }
        }
        jobsToCancel.forEach { it.cancel() }
    }

    actual fun clearLocalState() {
        val jobsToCancel = synchronized(profileLock) {
            profileGeneration += 1
            refreshJobs.values.toList().also {
                refreshJobs.clear()
                initialized = false
                currentProfileId = 1
                _uiState.value = CloudStreamUiState()
                CloudStreamPlatformRuntime.clear()
                CloudStreamPlatformStorage.setActiveProfile(1)
                CloudStreamPlatformStorage.clearPackages()
                CloudStreamPlatformStorage.clearAllState()
            }
        }
        jobsToCancel.forEach { it.cancel() }
    }

    actual fun acceptSecurityWarning() {
        initialize()
        synchronized(profileLock) {
            _uiState.update { it.copy(securityWarningAccepted = true) }
            persist()
        }
    }

    actual suspend fun addRepository(rawUrl: String): AddCloudStreamRepositoryResult {
        initialize()
        val expectedProfileGeneration = profileGenerationSnapshot()
        val manifestUrl = try {
            resolveCloudStreamRepositoryInput(rawUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return AddCloudStreamRepositoryResult.Error(error.message ?: "Invalid repository URL")
        }
        if (!isProfileGenerationCurrent(expectedProfileGeneration)) {
            return AddCloudStreamRepositoryResult.Error("Active profile changed while resolving the repository link")
        }
        if (_uiState.value.repositories.any { it.manifest.sourceUrl == manifestUrl }) {
            return AddCloudStreamRepositoryResult.Error("CloudStream repository is already added")
        }

        return runCatching { fetchRepository(manifestUrl) }
            .fold(
                onSuccess = { (repository, plugins) ->
                    var duplicate = false
                    val committed = commitForProfile(expectedProfileGeneration) {
                        duplicate = _uiState.value.repositories.any { it.manifest.sourceUrl == manifestUrl }
                        if (!duplicate) {
                            _uiState.update { current ->
                                current.copy(
                                    repositories = current.repositories + CloudStreamRepositoryItem(repository),
                                    plugins = (current.plugins + plugins.map(::newPluginItem))
                                        .distinctBy { it.metadata.id.value }
                                        .sortedBy { it.metadata.name.lowercase() },
                                )
                            }
                            persist()
                        }
                    }
                    when {
                        !committed -> AddCloudStreamRepositoryResult.Error("Active profile changed before the repository could be added")
                        duplicate -> AddCloudStreamRepositoryResult.Error("CloudStream repository is already added")
                        else -> AddCloudStreamRepositoryResult.Success(repository)
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    log.w(error) { "CloudStream repository install failed url=$manifestUrl" }
                    AddCloudStreamRepositoryResult.Error(error.message ?: "Could not load CloudStream repository")
                },
            )
    }

    actual fun refreshRepository(manifestUrl: String) {
        initialize()
        val expectedProfileGeneration = profileGenerationSnapshot()
        val normalizedUrl = runCatching { normalizeCloudStreamRepositoryUrl(manifestUrl) }.getOrNull() ?: return
        var jobToStart: Job? = null

        commitForProfile(expectedProfileGeneration) {
            val existingJob = refreshJobs[normalizedUrl]
            if (existingJob == null || existingJob.isCompleted) {
                if (_uiState.value.repositories.any { it.manifest.sourceUrl == normalizedUrl }) {
                    lateinit var job: Job
                    job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val refreshed = try {
                                fetchRepository(normalizedUrl)
                            } catch (error: CancellationException) {
                                commitForProfile(expectedProfileGeneration) {
                                    if (refreshJobs[normalizedUrl] === job) {
                                        _uiState.update { current ->
                                            current.copy(
                                                repositories = current.repositories.map { item ->
                                                    if (item.manifest.sourceUrl == normalizedUrl) {
                                                        item.copy(isRefreshing = false)
                                                    } else item
                                                },
                                            )
                                        }
                                    }
                                }
                                throw error
                            } catch (error: Throwable) {
                                log.w(error) { "CloudStream repository refresh failed url=$normalizedUrl" }
                                commitForProfile(expectedProfileGeneration) {
                                    if (refreshJobs[normalizedUrl] === job) {
                                        _uiState.update { current ->
                                            current.copy(
                                                repositories = current.repositories.map { item ->
                                                    if (item.manifest.sourceUrl == normalizedUrl) {
                                                        item.copy(
                                                            isRefreshing = false,
                                                            errorMessage = error.message ?: "Refresh failed",
                                                        )
                                                    } else item
                                                },
                                            )
                                        }
                                    }
                                }
                                return@launch
                            }

                            commitForProfile(expectedProfileGeneration) {
                                if (
                                    refreshJobs[normalizedUrl] === job &&
                                    _uiState.value.repositories.any { it.manifest.sourceUrl == normalizedUrl }
                                ) {
                                    applyRepositoryRefresh(refreshed.first, refreshed.second)
                                    persist()
                                }
                            }
                        } finally {
                            commitForProfile(expectedProfileGeneration) {
                                if (refreshJobs[normalizedUrl] === job) refreshJobs.remove(normalizedUrl)
                            }
                        }
                    }
                    refreshJobs[normalizedUrl] = job
                    _uiState.update { current ->
                        current.copy(
                            repositories = current.repositories.map { item ->
                                if (item.manifest.sourceUrl == normalizedUrl) {
                                    item.copy(isRefreshing = true, errorMessage = null)
                                } else item
                            },
                        )
                    }
                    jobToStart = job
                }
            }
        }
        jobToStart?.start()
    }

    actual fun refreshAll() {
        initialize()
        _uiState.value.repositories.forEach { refreshRepository(it.manifest.sourceUrl) }
    }

    actual fun removeRepository(manifestUrl: String) {
        initialize()
        val normalizedUrl = runCatching { normalizeCloudStreamRepositoryUrl(manifestUrl) }.getOrNull() ?: return
        val expectedProfileGeneration = profileGenerationSnapshot()
        var refreshJobToCancel: Job? = null
        commitForProfile(expectedProfileGeneration) {
            // Removing the registration before cancellation makes any late completion
            // from this job fail the identity check in its success/error handlers.
            refreshJobToCancel = refreshJobs.remove(normalizedUrl)
            val removedPlugins = _uiState.value.plugins
                .filter { it.metadata.repositoryManifestUrl == normalizedUrl }
            removedPlugins.forEach {
                CloudStreamPlatformRuntime.unload(it.metadata.id.value)
                CloudStreamPlatformStorage.deletePackage(it.metadata.id.storageKey)
            }
            _uiState.update { current ->
                current.copy(
                    repositories = current.repositories.filterNot { it.manifest.sourceUrl == normalizedUrl },
                    plugins = current.plugins.filterNot { it.metadata.repositoryManifestUrl == normalizedUrl },
                    registryRevision = current.registryRevision + 1,
                )
            }
            persist()
        }
        refreshJobToCancel?.cancel()
    }

    actual suspend fun installPlugin(pluginId: String): CloudStreamInstallResult = installOrUpdate(pluginId)

    actual suspend fun updatePlugin(pluginId: String): CloudStreamInstallResult = installOrUpdate(pluginId)

    actual suspend fun installAndEnablePlugins(pluginIds: List<String>): CloudStreamBulkInstallResult {
        initialize()
        val expectedProfileGeneration = profileGenerationSnapshot()
        val requestedIds = pluginIds.distinct()
        if (requestedIds.isEmpty()) {
            return CloudStreamBulkInstallResult(
                requestedCount = 0,
                installedCount = 0,
                enabledCount = 0,
                skippedCount = 0,
            )
        }
        if (!_uiState.value.securityWarningAccepted) {
            return CloudStreamBulkInstallResult(
                requestedCount = requestedIds.size,
                installedCount = 0,
                enabledCount = 0,
                skippedCount = 0,
                failures = listOf(
                    CloudStreamBulkInstallFailure(
                        pluginName = "CloudStream",
                        message = "Accept the third-party plugin security warning before installation",
                    ),
                ),
            )
        }

        var installedCount = 0
        var enabledCount = 0
        var skippedCount = 0
        val failures = mutableListOf<CloudStreamBulkInstallFailure>()

        requestedIds.forEach { pluginId ->
            if (profileGenerationSnapshot() != expectedProfileGeneration) {
                failures += CloudStreamBulkInstallFailure(
                    pluginName = pluginId,
                    message = "Active profile changed; remaining plugins were not installed",
                )
                return@forEach
            }
            val before = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            if (before == null) {
                failures += CloudStreamBulkInstallFailure(pluginName = pluginId, message = "CloudStream plugin was not found")
                return@forEach
            }
            if (before.isInstalling) {
                skippedCount += 1
                return@forEach
            }
            if (!before.metadata.status.canInstall) {
                failures += CloudStreamBulkInstallFailure(
                    pluginName = before.metadata.name,
                    message = "This CloudStream plugin is marked as down",
                )
                return@forEach
            }
            if (!before.compatibility.isRunnable) {
                failures += CloudStreamBulkInstallFailure(
                    pluginName = before.metadata.name,
                    message = before.compatibility.reason,
                )
                return@forEach
            }

            val needsInstall = !before.isInstalled || before.hasUpdate || !before.verified
            val installResult = if (needsInstall) installOrUpdate(pluginId, expectedProfileGeneration) else CloudStreamInstallResult.Success(before)
            when (installResult) {
                is CloudStreamInstallResult.Success -> {
                    if (needsInstall) installedCount += 1
                    val enabledInRequestedProfile = commitForProfile(expectedProfileGeneration) {
                        setPluginEnabledForCurrentProfile(pluginId, true)
                    }
                    if (!enabledInRequestedProfile) {
                        failures += CloudStreamBulkInstallFailure(
                            pluginName = installResult.plugin.metadata.name,
                            message = "Active profile changed before the plugin could be enabled",
                        )
                        return@forEach
                    }
                    val after = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
                    if (after?.isRunnable == true) {
                        enabledCount += 1
                    } else {
                        failures += CloudStreamBulkInstallFailure(
                            pluginName = installResult.plugin.metadata.name,
                            message = "The package was installed, but could not be enabled because verification or compatibility checks failed.",
                        )
                    }
                }
                is CloudStreamInstallResult.Error -> {
                    failures += CloudStreamBulkInstallFailure(
                        pluginName = before.metadata.name,
                        message = installResult.message,
                    )
                }
            }
        }

        return CloudStreamBulkInstallResult(
            requestedCount = requestedIds.size,
            installedCount = installedCount,
            enabledCount = enabledCount,
            skippedCount = skippedCount,
            failures = failures,
        )
    }

    actual fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        initialize()
        synchronized(profileLock) {
            setPluginEnabledForCurrentProfile(pluginId, enabled)
        }
    }

    private fun setPluginEnabledForCurrentProfile(pluginId: String, enabled: Boolean) {
        _uiState.update { current ->
            var changed = false
            val plugins = current.plugins.map { item ->
                if (item.metadata.id.value != pluginId) return@map item
                val nextEnabled = enabled && item.isInstalled && item.verified && item.compatibility.isRunnable &&
                    item.metadata.status.canInstall
                changed = item.enabled != nextEnabled
                item.copy(enabled = nextEnabled, errorMessage = null)
            }
            current.copy(
                plugins = plugins,
                registryRevision = current.registryRevision + if (changed) 1 else 0,
            )
        }
        if (!enabled) CloudStreamPlatformRuntime.unload(pluginId)
        persist()
    }

    actual fun removePlugin(pluginId: String) {
        initialize()
        val expectedProfileGeneration = profileGenerationSnapshot()
        commitForProfile(expectedProfileGeneration) {
            val plugin = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
                ?: return@commitForProfile
            CloudStreamPlatformRuntime.unload(pluginId)
            CloudStreamPlatformStorage.deletePackage(plugin.metadata.id.storageKey)
            _uiState.update { current ->
                current.copy(
                    plugins = current.plugins.map { item ->
                        if (item.metadata.id.value == pluginId) {
                            item.copy(
                                installedVersion = null,
                                installedAtEpochMs = 0L,
                                installedFileHash = null,
                                enabled = false,
                                verified = false,
                                isInstalling = false,
                                errorMessage = null,
                            )
                        } else item
                    },
                    registryRevision = current.registryRevision + 1,
                )
            }
            persist()
        }
    }

    actual suspend fun getMainPage(
        providerId: String,
        page: Int,
    ): Result<List<Pair<String, List<CloudStreamSearchItem>>>> = providerResult(providerId) {
        getMainPage(page.coerceAtLeast(1))
    }

    actual suspend fun search(
        query: String,
        providerId: String?,
    ): List<Result<List<CloudStreamSearchItem>>> {
        initialize()
        val activeIds = runnableProviderIds().filter { providerId == null || it == providerId }
        return activeIds.map { id ->
            providerResult(id) {
                search(query.trim()).also { items -> CloudStreamSearchRouteIndex.remember(id, query, items) }
            }
        }
    }

    actual suspend fun load(providerId: String, data: String): Result<CloudStreamLoadItem> =
        providerResult(providerId) { load(data) }

    actual suspend fun loadByExternalId(
        providerId: String,
        externalId: String,
    ): Result<CloudStreamLoadItem?> = providerResult(providerId) {
        loadByExternalId(externalId)
    }

    actual suspend fun loadLinks(providerId: String, data: String): Result<List<CloudStreamPlaybackSource>> =
        providerResult(providerId) { loadLinks(data) }

    private suspend fun installOrUpdate(
        pluginId: String,
        expectedGeneration: Long? = null,
    ): CloudStreamInstallResult {
        initialize()
        val expectedProfileGeneration = expectedGeneration ?: profileGenerationSnapshot()
        if (!isProfileGenerationCurrent(expectedProfileGeneration)) {
            return CloudStreamInstallResult.Error("Active profile changed before installation started")
        }
        if (!_uiState.value.securityWarningAccepted) {
            return CloudStreamInstallResult.Error("Accept the third-party plugin security warning before installation")
        }
        val item = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            ?: return CloudStreamInstallResult.Error("CloudStream plugin was not found")
        if (!item.metadata.status.canInstall) {
            return CloudStreamInstallResult.Error("This CloudStream plugin is marked as down")
        }
        if (!item.compatibility.isRunnable) {
            return CloudStreamInstallResult.Error(item.compatibility.reason)
        }
        // Never replace a locally verified package with an unverified update. The
        // installed artifact may remain usable while the repository's new version
        // is missing its checksum, but overwriting it would discard that guarantee.
        if (item.isInstalled && item.verified && item.metadata.fileHash == null) {
            return CloudStreamInstallResult.Error(
                "This repository does not provide a SHA-256 hash. The previously verified package was preserved; this update cannot be installed safely.",
            )
        }
        var installationStarted = false
        val startCommitted = commitForProfile(expectedProfileGeneration) {
            val latest = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            if (latest != null && !latest.isInstalling && latest.metadata == item.metadata) {
                markInstalling(pluginId, true, null)
                installationStarted = true
            }
        }
        if (!startCommitted) {
            return CloudStreamInstallResult.Error("Active profile changed before installation started")
        }
        if (!installationStarted) {
            return CloudStreamInstallResult.Error("CloudStream plugin is already being installed or has changed")
        }

        return runCatching {
            val response = httpRequestRaw(
                method = "GET",
                url = item.metadata.packageUrl,
                headers = mapOf("Accept" to "application/zip, application/octet-stream"),
                body = "",
                maxResponseBodyBytes = 50 * 1024 * 1024,
            )
            require(response.status in 200..299) {
                "CloudStream package download failed with HTTP ${response.status}"
            }
            require(!response.body.endsWith("\n...[truncated]")) {
                "CloudStream package exceeds the 50 MB download limit"
            }
            val bytes = response.bodyBytes
            require(bytes.isNotEmpty()) { "Downloaded .cs3 package is empty" }
            item.metadata.fileSize?.let { expectedSize ->
                if (bytes.size.toLong() != expectedSize) {
                    log.w {
                        "CloudStream package metadata size mismatch id=$pluginId expected=$expectedSize actual=${bytes.size}"
                    }
                }
            }
            CloudStreamPackageInspector.inspect(bytes)
            val expectedHash = item.metadata.fileHash
            val verified = expectedHash.verifies(bytes)
            if (expectedHash != null) require(verified) { ".cs3 SHA-256 hash mismatch" }

            var installed: CloudStreamPluginItem? = null
            val committed = commitForProfile(expectedProfileGeneration) {
                val currentItem = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
                    ?: error("CloudStream plugin was removed before installation completed")
                check(currentItem.metadata == item.metadata && currentItem.isInstalling) {
                    "CloudStream plugin was removed, changed, or its installation was cancelled"
                }
                CloudStreamPlatformRuntime.unload(pluginId)
                CloudStreamPlatformStorage.savePackageAtomically(item.metadata.id.storageKey, bytes)
                installed = currentItem.copy(
                    installedVersion = currentItem.metadata.version,
                    installedAtEpochMs = currentEpochMillis(),
                    installedFileHash = expectedHash?.wireValue,
                    enabled = currentItem.enabled && verified && currentItem.compatibility.isRunnable,
                    verified = verified,
                    isInstalling = false,
                    errorMessage = null,
                )
                _uiState.update { current ->
                    current.copy(
                        plugins = current.plugins.map { plugin ->
                            if (plugin.metadata.id.value == pluginId) installed!! else plugin
                        },
                        registryRevision = current.registryRevision + 1,
                    )
                }
                persist()
            }
            check(committed) { "Active profile changed during installation; no package was committed" }
            requireNotNull(installed)
        }.fold(
            onSuccess = { CloudStreamInstallResult.Success(it) },
            onFailure = { error ->
                if (error is CancellationException) {
                    commitForProfile(expectedProfileGeneration) { markInstalling(pluginId, false, null) }
                    throw error
                }
                log.w(error) { "CloudStream plugin installation failed id=$pluginId" }
                commitForProfile(expectedProfileGeneration) {
                    markInstalling(pluginId, false, error.message ?: "Plugin installation failed")
                }
                CloudStreamInstallResult.Error(error.message ?: "Plugin installation failed")
            },
        )
    }

    private suspend fun fetchRepository(
        manifestUrl: String,
    ): Pair<CloudStreamRepositoryManifest, List<CloudStreamPluginMetadata>> {
        var lastManifestError: Throwable? = null
        val manifest = cloudStreamManifestCandidates(manifestUrl).firstNotNullOfOrNull { candidate ->
            runCatching {
                CloudStreamRepositoryParser.parseRepository(candidate, httpGetText(candidate))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                lastManifestError = error
                log.w(error) { "CloudStream repository manifest failed url=$candidate" }
            }.getOrNull()
        } ?: throw lastManifestError ?: IllegalStateException("CloudStream repository manifest could not be loaded")
        // A repository may publish multiple plugin lists. Applying only the lists
        // that happened to load would make a transient CDN failure look like an
        // intentional removal and silently drop installed plugins from persisted state.
        // Treat the refresh as atomic: keep the previous snapshot unless every declared
        // list was fetched and parsed successfully.
        val lists = manifest.pluginListUrls.map { pluginListUrl ->
            try {
                val listPayload = httpGetText(pluginListUrl)
                CloudStreamRepositoryParser.parsePluginList(
                    repositoryManifestUrl = normalizeCloudStreamRepositoryUrl(manifestUrl),
                    pluginListUrl = pluginListUrl,
                    payload = listPayload,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "CloudStream plugin list failed url=$pluginListUrl" }
                throw IllegalStateException(
                    "CloudStream repository plugin list could not be loaded: $pluginListUrl",
                    error,
                )
            }
        }
        // Keep the repository identity tied to the URL the user added. The fetched
        // manifest may have come from the main/master fallback candidate; persisting
        // that fallback URL would fail to match the existing repository during refresh.
        val stableManifest = retainCloudStreamRepositoryIdentity(
            requestedManifestUrl = manifestUrl,
            resolvedManifest = manifest,
        )
        return stableManifest to CloudStreamRepositoryParser.mergePluginLists(lists)
    }

    private fun cloudStreamManifestCandidates(manifestUrl: String): List<String> {
        val normalized = normalizeCloudStreamRepositoryUrl(manifestUrl)
        val alternate = when {
            "/master/repo.json" in normalized -> normalized.replace("/master/repo.json", "/main/repo.json")
            "/main/repo.json" in normalized -> normalized.replace("/main/repo.json", "/master/repo.json")
            else -> null
        }
        return listOfNotNull(normalized, alternate).distinct()
    }

    private fun applyRepositoryRefresh(
        repository: CloudStreamRepositoryManifest,
        plugins: List<CloudStreamPluginMetadata>,
    ) {
        val previous = _uiState.value.plugins
            .filter { it.metadata.repositoryManifestUrl == repository.sourceUrl }
        val refreshedIds = plugins.mapTo(mutableSetOf()) { it.id.value }
        // Stop removed providers and do not leave a stale checked/enabled state when
        // a repository changes a plugin's status or runtime compatibility.
        previous
            .filterNot { it.metadata.id.value in refreshedIds }
            .forEach { removed ->
                CloudStreamPlatformRuntime.unload(removed.metadata.id.value)
                CloudStreamPlatformStorage.deletePackage(removed.metadata.id.storageKey)
            }

        _uiState.update { current ->
            val previousById = current.plugins.associateBy { it.metadata.id.value }
            val refreshed = plugins.map { metadata ->
                val previousItem = previousById[metadata.id.value]
                val compatibility = resolveCompatibility(metadata)
                previousItem?.let { previous ->
                    val installedPackageMatchesCurrentMetadata = previous.installedVersion != metadata.version ||
                        when {
                            metadata.fileHash != null ->
                                previous.installedFileHash == metadata.fileHash.wireValue
                            previous.metadata.packageUrl != metadata.packageUrl -> false
                            else -> true
                        }
                    val verified = previous.verified && installedPackageMatchesCurrentMetadata
                    previous.copy(
                        metadata = metadata,
                        compatibility = compatibility,
                        verified = verified,
                        enabled = previous.enabled && verified && metadata.status.canInstall && compatibility.isRunnable,
                        isInstalling = false,
                        errorMessage = null,
                    )
                } ?: newPluginItem(metadata)
            }
            current.copy(
                repositories = current.repositories.map { item ->
                    if (item.manifest.sourceUrl == repository.sourceUrl) {
                        CloudStreamRepositoryItem(repository)
                    } else item
                },
                plugins = (current.plugins.filterNot { it.metadata.repositoryManifestUrl == repository.sourceUrl } + refreshed)
                    .sortedBy { it.metadata.name.lowercase() },
                registryRevision = current.registryRevision + 1,
            )
        }

        _uiState.value.plugins
            .filter { it.metadata.repositoryManifestUrl == repository.sourceUrl && !it.isRunnable }
            .forEach { item -> CloudStreamPlatformRuntime.unload(item.metadata.id.value) }
    }

    private fun newPluginItem(metadata: CloudStreamPluginMetadata): CloudStreamPluginItem =
        CloudStreamPluginItem(
            metadata = metadata,
            compatibility = resolveCompatibility(metadata),
        )

    private fun markInstalling(pluginId: String, installing: Boolean, error: String?) {
        _uiState.update { current ->
            current.copy(
                plugins = current.plugins.map { item ->
                    if (item.metadata.id.value == pluginId) {
                        item.copy(isInstalling = installing, errorMessage = error)
                    } else item
                },
            )
        }
    }

    private fun restoreState(profileId: Int): CloudStreamUiState {
        val payload = CloudStreamPlatformStorage.loadState(profileId) ?: return CloudStreamUiState()
        val stored = runCatching { json.decodeFromString<StoredCloudStreamState>(payload) }
            .onFailure { log.w(it) { "Could not restore CloudStream state" } }
            .getOrNull() ?: return CloudStreamUiState()
        val repositories = stored.repositories.map { repository ->
            val manifestUrl = runCatching { normalizeCloudStreamRepositoryUrl(repository.manifestUrl) }
                .getOrDefault(repository.manifestUrl)
            CloudStreamRepositoryItem(
                CloudStreamRepositoryManifest(
                    sourceUrl = manifestUrl,
                    name = repository.name,
                    description = repository.description,
                    iconUrl = repository.iconUrl,
                    manifestVersion = repository.manifestVersion,
                    pluginListUrls = repository.pluginListUrls,
                ),
            )
        }
        val plugins = stored.plugins.map { plugin ->
            val metadata = plugin.toMetadata()
            val legacyStorageKey = CloudStreamPluginId(plugin.repositoryManifestUrl, plugin.internalName).storageKey
            val storageKey = metadata.id.storageKey
            val packageExists = plugin.installedVersion != null && (
                CloudStreamPlatformStorage.packageExists(storageKey) ||
                    (legacyStorageKey != storageKey &&
                        CloudStreamPlatformStorage.migratePackage(legacyStorageKey, storageKey))
                )
            val compatibility = resolveCompatibility(metadata)
            CloudStreamPluginItem(
                metadata = metadata,
                compatibility = compatibility,
                installedVersion = plugin.installedVersion.takeIf { packageExists },
                installedAtEpochMs = plugin.installedAtEpochMs.takeIf { packageExists } ?: 0L,
                enabled = plugin.enabled && packageExists && plugin.verified &&
                    CloudStreamFileHash.parse(plugin.installedFileHash ?: plugin.fileHash.takeIf {
                        plugin.installedVersion == plugin.availableVersion
                    }) != null && compatibility.isRunnable && metadata.status.canInstall,
                verified = plugin.verified && packageExists && CloudStreamFileHash.parse(
                    plugin.installedFileHash ?: plugin.fileHash.takeIf {
                        plugin.installedVersion == plugin.availableVersion
                    },
                ) != null,
                installedFileHash = plugin.installedFileHash
                    ?: plugin.fileHash.takeIf { plugin.installedVersion == plugin.availableVersion && packageExists },
            )
        }
        return CloudStreamUiState(
            repositories = repositories,
            plugins = plugins,
            registryRevision = 1,
            securityWarningAccepted = stored.securityWarningAccepted,
        )
    }

    private fun persist() {
        val current = _uiState.value
        val stored = StoredCloudStreamState(
            repositories = current.repositories.map { item ->
                StoredCloudStreamRepository(
                    manifestUrl = item.manifest.sourceUrl,
                    name = item.manifest.name,
                    description = item.manifest.description,
                    iconUrl = item.manifest.iconUrl,
                    manifestVersion = item.manifest.manifestVersion,
                    pluginListUrls = item.manifest.pluginListUrls,
                )
            },
            plugins = current.plugins.map { item -> item.toStored() },
            securityWarningAccepted = current.securityWarningAccepted,
        )
        CloudStreamPlatformStorage.saveState(currentProfileId, json.encodeToString(stored))
    }

    private fun CloudStreamPluginItem.toStored(): StoredCloudStreamPlugin = StoredCloudStreamPlugin(
        repositoryManifestUrl = metadata.repositoryManifestUrl,
        packageUrl = metadata.packageUrl,
        status = metadata.status.name,
        availableVersion = metadata.version,
        installedVersion = installedVersion,
        name = metadata.name,
        internalName = metadata.internalName,
        authors = metadata.authors,
        description = metadata.description,
        fileSize = metadata.fileSize,
        projectUrl = metadata.projectUrl,
        language = metadata.language,
        rawTvTypes = metadata.rawTvTypes,
        iconUrl = metadata.iconUrl,
        apiVersion = metadata.apiVersion,
        fileHash = metadata.fileHash?.wireValue,
        installedFileHash = installedFileHash,
        enabled = enabled,
        verified = verified,
        installedAtEpochMs = installedAtEpochMs.takeIf { installedVersion != null } ?: 0L,
    )

    private fun StoredCloudStreamPlugin.toMetadata(): CloudStreamPluginMetadata {
        val normalizedRepositoryUrl = runCatching { normalizeCloudStreamRepositoryUrl(repositoryManifestUrl) }
            .getOrDefault(repositoryManifestUrl)
        return CloudStreamPluginMetadata(
            id = CloudStreamPluginId(normalizedRepositoryUrl, internalName),
            repositoryManifestUrl = normalizedRepositoryUrl,
            packageUrl = packageUrl,
            status = runCatching { CloudStreamPluginStatus.valueOf(status) }
                .getOrDefault(CloudStreamPluginStatus.Unknown),
            version = availableVersion,
            name = name,
            internalName = internalName,
            authors = authors,
            description = description,
            fileSize = fileSize,
            projectUrl = projectUrl,
            language = language,
            tvTypes = rawTvTypes.map(CloudStreamTvType::fromWireValue).distinct(),
            rawTvTypes = rawTvTypes,
            iconUrl = iconUrl,
            apiVersion = apiVersion,
            fileHash = CloudStreamFileHash.parse(fileHash),
        )
    }

    private fun runnableProviderIds(): List<String> =
        _uiState.value.plugins.filter(CloudStreamPluginItem::isRunnable).map { it.metadata.id.value }

    private fun resolveCompatibility(metadata: CloudStreamPluginMetadata): CloudStreamCompatibility =
        CloudStreamCompatibilityResolver.resolve(metadata)

    private suspend fun <T> providerResult(
        providerId: String,
        block: suspend CloudStreamProvider.() -> T,
    ): Result<T> {
        initialize()
        if (providerId !in runnableProviderIds()) {
            return Result.failure(IllegalStateException("CloudStream provider is disabled or incompatible"))
        }
        return try {
            val plugin = _uiState.value.plugins.firstOrNull { it.metadata.id.value == providerId }
                ?: error("CloudStream plugin was not found")
            val provider = CloudStreamProviderRegistry.find(providerId)
                ?: CloudStreamPlatformRuntime.provider(plugin)
                ?: error("CloudStream provider adapter was not found")
            Result.success(provider.block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.w(error) { "CloudStream provider request failed id=$providerId" }
            Result.failure(error)
        }
    }

    private suspend fun resolveCloudStreamRepositoryInput(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val expanded = when {
            trimmed.startsWith("!", ignoreCase = false) ->
                resolveCloudStreamShortLink("https://py.md/${trimmed.removePrefix("!")}")
            trimmed.matches(Regex("^[a-zA-Z0-9!_-]+$")) ->
                resolveCloudStreamShortLink("https://cutt.ly/$trimmed")
            else -> trimmed
        }
        return normalizeCloudStreamRepositoryUrl(expanded)
    }

    private suspend fun resolveCloudStreamShortLink(url: String): String {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = emptyMap(),
            body = "",
            followRedirects = false,
        )
        val location = response.headers.entries.firstOrNull { (name, _) -> name.equals("location", ignoreCase = true) }?.value?.trim()
        require(!location.isNullOrBlank()) { "CloudStream short repository link did not redirect" }
        require(!location.startsWith("https://py.md/404") && !location.startsWith("https://cutt.ly/404")) {
            "CloudStream short repository link was not found"
        }
        return location
    }
}
