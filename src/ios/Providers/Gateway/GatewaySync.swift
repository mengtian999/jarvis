import Foundation
import os.log

// MARK: - GatewaySyncResult

/// Result of a gateway model catalog sync attempt.
struct GatewaySyncResult {
    let ok: Bool
    let models: [String]
    let imageModels: [String]
    let videoModels: [String]
    let defaultModelId: String?
    let deviceId: String?
    let error: String?

    static let notReached = GatewaySyncResult(
        ok: false, models: [], imageModels: [], videoModels: [], defaultModelId: nil, deviceId: nil,
        error: "Gateway unreachable"
    )
}

// MARK: - GatewaySync

/// Gateway model catalog sync — mirrors desktop `core/gateway/gateway-sync.ts`.
///
/// Fetches /v1/models from the gateway (signed, Route A) and upserts
/// a managed ProviderInstance + ModelEntries into ProviderConfigStore.
///
/// Failure semantics (same as desktop): gateway unreachable → preserve
/// existing config (no clear, no block) → return ok:false so the caller
/// can decide when to show a UI hint.
@MainActor
enum GatewaySync {
    private static let logger = AppLogger(category: "GatewaySync")
    private static let keyAgentLoopMediaSeeded = "gateway_agent_loop_media_models_seeded"

    /// Stable instance ID for the gateway provider (idempotent re-syncs).
    static let instanceId = "jarvis-gateway"
    static let providerLabel = "Jarvis Cloud"
    static let providerName = "Jarvis Cloud" // LLMModel.provider field

    // MARK: - Sync

    /// Sync gateway models into ProviderConfigStore.
    /// Safe to call on every app launch — no-op if the gateway is unreachable.
    static func sync(client: GatewayClient = .shared) async -> GatewaySyncResult {
        let identity: GatewayIdentity
        do {
            identity = try await client.ensureDevice()
        } catch {
            logger.warning("Device registration failed: \(error.localizedDescription)")
            return .notReached
        }

        let modelsJSON: [String: Any]
        do {
            modelsJSON = try await client.listModels()
        } catch {
            logger.warning("Model list fetch failed: \(error.localizedDescription)")
            return .notReached
        }

        let region = identity.region
        let chatEntries = pickModels(modelsJSON, region: region, kind: "chat")
        let imageEntries = pickModels(modelsJSON, region: region, kind: "image")
        let videoEntries = pickModels(modelsJSON, region: region, kind: "video")

        guard !chatEntries.isEmpty else {
            logger.warning("Gateway model list is empty for region=\(region)")
            return GatewaySyncResult(
                ok: false, models: [], imageModels: [], videoModels: [], defaultModelId: nil,
                deviceId: identity.deviceId, error: "Gateway model list is empty"
            )
        }

        let chatModels = chatEntries.map { tier in
            LLMModel(id: tier.id, displayName: tier.displayName, provider: providerName)
        }
        let imageModels = imageEntries.map { tier -> LLMModel in
            let tierId = tier.id.hasPrefix("image") ? tier.id : "image/\(tier.id)"
            let displayName = (tier.displayName.contains("图") || tier.displayName.localizedCaseInsensitiveContains("image"))
                ? tier.displayName
                : "\(tier.displayName) (图片)"
            var model = LLMModel(
                id: tierId,
                displayName: displayName,
                provider: providerName
            )
            model.modalityOverride = [.textInput, .imageInput, .imageOutput]
            return model
        }
        let videoModels = videoEntries.map { tier -> LLMModel in
            let tierId = tier.id.hasPrefix("video") ? tier.id : "video/\(tier.id)"
            let displayName = (tier.displayName.contains("视") || tier.displayName.localizedCaseInsensitiveContains("video"))
                ? tier.displayName
                : "\(tier.displayName) (视频)"
            var model = LLMModel(
                id: tierId,
                displayName: displayName,
                provider: providerName
            )
            model.modalityOverride = [.textInput, .imageInput, .videoOutput]
            return model
        }
        let allModels = chatModels + imageModels + videoModels

        upsertGatewayInstance(in: ProviderConfigStore.shared, client: client)
        ProviderKeychainHelper.saveAPIKey(
            identity.token, instanceId: instanceId, caller: "GatewaySync"
        )
        ProviderConfigStore.shared.replaceEntries(
            for: instanceId, models: allModels, caller: "GatewaySync"
        )
        ensureDefaultModelGroup(in: ProviderConfigStore.shared)
        ensureDefaultAgentLoopMediaModels(in: ProviderConfigStore.shared)

        let defaultModelId = chatEntries.first(where: { $0.isDefault })?.id
            ?? (chatEntries.contains(where: { $0.id == "auto" }) ? "auto" : chatEntries.first?.id)

        logger.info("Synced: \(chatModels.count) chat, \(imageEntries.count) image, \(videoEntries.count) video, region=\(region)")

        return GatewaySyncResult(
            ok: true,
            models: chatModels.map { $0.id },
            imageModels: imageEntries.map { $0.id },
            videoModels: videoEntries.map { $0.id },
            defaultModelId: defaultModelId,
            deviceId: identity.deviceId
        )
    }

    // MARK: - Model Parsing

    /// A parsed gateway tier view from /v1/models.
    struct GatewayTier {
        let id: String
        let displayName: String
        let isDefault: Bool
    }

    /// Parse /v1/models response, picking tiers of a given kind for a region.
    /// Tries grouped `pools[region][kind]` first, then falls back to the flat
    /// `data[]` array filtered by `kind` + `region` (for older gateway versions).
    static func pickModels(
        _ json: [String: Any], region: String, kind: String
    ) -> [GatewayTier] {
        // Grouped: pools[region][kind]
        if let pools = json["pools"] as? [String: Any],
           let pool = pools[region] as? [String: Any],
           let tiers = pool[kind] as? [[String: Any]] {
            let parsed = tiers.compactMap { parseTier($0) }
            if !parsed.isEmpty { return parsed }
        }
        // Flat: data[] filtered by kind + region
        guard let data = json["data"] as? [[String: Any]] else { return [] }
        return data.compactMap { entry -> GatewayTier? in
            guard let id = entry["id"] as? String, !id.isEmpty else { return nil }
            let entryKind = entry["kind"] as? String
            let entryRegion = entry["region"] as? String
            // kind match: nil kind = treat as chat (OpenAI-compat default)
            let kindOK = (entryKind == nil) || (entryKind == kind)
            let regionOK = (entryRegion == nil) || (entryRegion == region)
            guard kindOK && regionOK else { return nil }
            return parseTier(entry)
        }
    }

    private static func parseTier(_ entry: [String: Any]) -> GatewayTier? {
        guard let id = entry["id"] as? String, !id.isEmpty else { return nil }
        let displayName = (entry["display_name"] as? String) ?? id
        let isDefault = (entry["is_default"] as? Bool) ?? false
        return GatewayTier(id: id, displayName: displayName, isDefault: isDefault)
    }

    // MARK: - Instance Upsert

    /// Upsert the gateway provider instance. If it doesn't exist, create it
    /// WITHOUT triggering the default model refresh (we manage entries ourselves
    /// via `replaceEntries` with chat-only filtering). If it exists, update the
    /// base URL in case it changed.
    static func upsertGatewayInstance(
        in store: ProviderConfigStore, client: GatewayClient
    ) {
        let baseURL = client.baseURL // without /v1; appendV1Suffix=true adds it

        if let existing = store.instances.first(where: { $0.id == instanceId }) {
            if existing.customBaseURL != baseURL {
                var updated = existing
                updated.customBaseURL = baseURL
                store.updateInstance(updated)
            }
            return
        }

        let instance = ProviderInstance(
            id: instanceId,
            label: providerLabel,
            providerType: .openAI,
            credentialType: .apiKey,
            isEnabled: true,
            createdAt: Date(),
            customBaseURL: baseURL,
            appendV1Suffix: true
        )
        store.addInstance(instance, skipModelRefresh: true)
    }

    // MARK: - Default Tier Selection

    static func isOfficeTier(_ entry: ModelEntry) -> Bool {
        let id = entry.baseModel.id
        let name = entry.model.displayName
        return id.caseInsensitiveCompare("office") == .orderedSame ||
            id.localizedCaseInsensitiveContains("office") ||
            name.contains("办公") ||
            name.localizedCaseInsensitiveContains("office")
    }

    static func isRoleTier(_ entry: ModelEntry) -> Bool {
        let id = entry.baseModel.id
        let name = entry.model.displayName
        return id.caseInsensitiveCompare("role") == .orderedSame ||
            id.localizedCaseInsensitiveContains("role") ||
            name.contains("角色") ||
            name.localizedCaseInsensitiveContains("role")
    }

    static func isCodingTier(_ entry: ModelEntry) -> Bool {
        let id = entry.baseModel.id
        let name = entry.model.displayName
        return id.caseInsensitiveCompare("coding") == .orderedSame ||
            id.localizedCaseInsensitiveContains("coding") ||
            name.contains("代码") ||
            name.localizedCaseInsensitiveContains("coding")
    }

    static func isAutoTier(_ entry: ModelEntry) -> Bool {
        let id = entry.baseModel.id
        let name = entry.model.displayName
        return id.caseInsensitiveCompare("auto") == .orderedSame ||
            id.localizedCaseInsensitiveContains("auto") ||
            name.contains("贾维斯") ||
            name.contains("通用") ||
            name.contains("智能推荐") ||
            name.localizedCaseInsensitiveContains("auto")
    }

    static func defaultGatewayTierEntryIds(from entries: [ModelEntry]) -> [String] {
        let gateway = entries.filter { $0.providerInstanceId == instanceId && !$0.isHidden }
        let target = gateway.isEmpty ? entries.filter { !$0.isHidden } : gateway
        let autoEntry = target.first(where: { isAutoTier($0) })
        let officeEntry = target.first(where: { isOfficeTier($0) })
        let roleEntry = target.first(where: { isRoleTier($0) })

        var preferred: [ModelEntry] = []
        for e in [autoEntry, officeEntry, roleEntry].compactMap({ $0 }) {
            if !preferred.contains(where: { $0.id == e.id }) {
                preferred.append(e)
            }
        }
        if preferred.count >= 3 {
            return Array(preferred.prefix(3).map(\.id))
        }

        let nonCoding = target.filter { !isCodingTier($0) }
        for e in (nonCoding + target) {
            if !preferred.contains(where: { $0.id == e.id }) {
                preferred.append(e)
            }
        }
        return Array(preferred.prefix(3).map(\.id))
    }

    /// First-install experience: when the user has no model groups yet, auto-create
    /// a "Default Models" group seeded with the gateway's preferred chat tiers (capped
    /// at 3: auto / office / role — the 4th tier "coding" stays available but unchecked)
    /// and set it as the default primary group. The user can enter a conversation
    /// directly without opening model selection.
    ///
    /// Also migrates existing "Default Models" groups if they contain the "coding" tier
    /// and are missing "role" or "office".
    ///
    /// No-op when any group already exists, so users who configured models
    /// manually are never touched. Mirrors Android GatewaySync.
    static func ensureDefaultModelGroup(in store: ProviderConfigStore) {
        let gatewayEntries = store.modelEntries.filter { $0.providerInstanceId == instanceId && !$0.isHidden }

        if let existingGroup = store.modelGroups.first(where: { $0.name == "Default Models" }) {
            let codingEntry = gatewayEntries.first(where: { isCodingTier($0) })
            let roleEntry = gatewayEntries.first(where: { isRoleTier($0) })
            let officeEntry = gatewayEntries.first(where: { isOfficeTier($0) })
            let targetReplacement: ModelEntry?
            if let role = roleEntry, !existingGroup.memberEntryIds.contains(role.id) {
                targetReplacement = role
            } else if let office = officeEntry, !existingGroup.memberEntryIds.contains(office.id) {
                targetReplacement = office
            } else {
                targetReplacement = nil
            }
            if let coding = codingEntry, let rep = targetReplacement,
               existingGroup.memberEntryIds.contains(coding.id) {
                var updated = existingGroup
                updated.memberEntryIds = existingGroup.memberEntryIds.map { $0 == coding.id ? rep.id : $0 }
                store.updateGroup(updated)
                logger.info("Migrated Default Models group: replaced coding tier with \(rep.baseModel.id) tier")
            }
            return
        }

        guard store.modelGroups.isEmpty else { return }

        let ids = defaultGatewayTierEntryIds(from: gatewayEntries)
        guard !ids.isEmpty else { return }

        let group = ModelGroup(name: "Default Models", memberEntryIds: ids)
        store.addGroup(group)
        if store.defaultPrimaryGroupId == nil {
            store.defaultPrimaryGroupId = group.id
        }
        logger.info("Auto-created Default Models group with \(ids.count) gateway tier(s)")
    }

    // MARK: - Agent Loop Media Models

    private static let keyAgentLoopMediaSeeded = "gateway_agent_loop_media_models_seeded"

    /// Seeds gateway image and video models into the Agent Loop usable models list
    /// (Settings > Model Groups > Usable Models in Agent Loop) on initial setup.
    /// Uses UserDefaults flag so if the user explicitly unpins/removes them later,
    /// subsequent syncs won't re-add them.
    static func ensureDefaultAgentLoopMediaModels(in store: ProviderConfigStore) {
        guard !UserDefaults.standard.bool(forKey: keyAgentLoopMediaSeeded) else { return }

        let mediaEntries = store.modelEntries.filter { entry in
            entry.providerInstanceId == instanceId && !entry.isHidden &&
                (entry.model.modalityOverride?.contains(.imageOutput) == true ||
                 entry.model.modalityOverride?.contains(.videoOutput) == true)
        }
        guard !mediaEntries.isEmpty else { return }

        for entry in mediaEntries {
            store.addAgentLoopEntry(entry.id)
        }
        UserDefaults.standard.set(true, forKey: keyAgentLoopMediaSeeded)
        logger.info("Seeded \(mediaEntries.count) gateway media model(s) into Agent Loop usable set")
    }
}

