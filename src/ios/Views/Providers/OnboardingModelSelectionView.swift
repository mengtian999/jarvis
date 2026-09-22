import SwiftUI

/// Onboarding step 2: pick one or more models from all configured providers and create a "Default Models" group.
struct OnboardingModelSelectionView: View {
    @ObservedObject private var store = ProviderConfigStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var selectedModelEntryIds: [String] = []
    @State private var searchText: String = ""

    /// All visible model entries across all enabled instances.
    private var allEntries: [ModelEntry] {
        store.instances
            .filter(\.isEnabled)
            .flatMap { store.visibleEntries(for: $0.id) }
    }

    var body: some View {
        List {
            if allEntries.isEmpty {
                Section {
                    HStack {
                        Spacer()
                        VStack(spacing: 8) {
                            ProgressView()
                            Text("Loading models...")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .padding(.vertical, 8)
                } header: {
                    Text("Models")
                } footer: {
                    Text("Fetching model list from your provider…")
                }
            } else {
                // Group entries by provider instance
                let instanceIds = store.instances.filter(\.isEnabled).map(\.id)
                ForEach(instanceIds, id: \.self) { instanceId in
                    let entries = store.visibleEntries(for: instanceId).filter { entry in
                        searchText.isEmpty || entry.model.displayName.localizedCaseInsensitiveContains(searchText)
                    }
                    if !entries.isEmpty, let instance = store.instance(for: instanceId) {
                        Section {
                            ForEach(entries) { entry in
                                modelRow(entry: entry)
                            }
                        } header: {
                            Text(instance.label)
                        }
                    }
                }

            }
        }
        .onAppear {
            if selectedModelEntryIds.isEmpty {
                selectedModelEntryIds = preseededSelections()
            }
        }
        .searchable(text: $searchText, prompt: "Filter models")
        .navigationTitle("Select Models")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Skip") { dismiss() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Next") { createGroupAndDismiss() }
                    .disabled(selectedModelEntryIds.isEmpty)
            }
        }
    }

    @ViewBuilder
    private func modelRow(entry: ModelEntry) -> some View {
        let selectionIndex = selectedModelEntryIds.firstIndex(of: entry.id)
        let isSelected = selectionIndex != nil

        Button {
            if let idx = selectionIndex {
                selectedModelEntryIds.remove(at: idx)
            } else {
                selectedModelEntryIds.append(entry.id)
            }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(isSelected ? Color.accentColor : Color(UIColor.tertiarySystemFill))
                        .frame(width: 26, height: 26)
                    if isSelected, let idx = selectionIndex {
                        Text("\(idx + 1)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                    }
                }
                Text(entry.model.displayName)
                    .font(.body)
                    .foregroundStyle(Color(UIColor.label))
                Spacer()
            }
        }
    }

    /// Pre-seed the selection so gateway tiers arrive pre-checked (mirrors
    /// Android `initialModelPreselections`):
    /// - an existing "Default Models" group's members, so re-opening the screen
    ///   shows the active selection;
    /// - otherwise the preferred gateway catalog tiers, capped at 3 (auto / office / role;
    ///   the 4th tier "coding" stays unchecked by design).
    private func preseededSelections() -> [String] {
        let enabled = store.instances.filter(\.isEnabled).map(\.id)
        let visibleIds = enabled
            .flatMap { store.visibleEntries(for: $0).map(\.id) }
            .reduce(into: Set<String>()) { $0.insert($1) }
        let gatewayEntries = store.visibleEntries(for: GatewaySync.instanceId)
        if let group = store.modelGroups.first(where: { $0.name == "Default Models" }) {
            let codingEntry = gatewayEntries.first(where: { GatewaySync.isCodingTier($0) })
            let roleEntry = gatewayEntries.first(where: { GatewaySync.isRoleTier($0) })
            let officeEntry = gatewayEntries.first(where: { GatewaySync.isOfficeTier($0) })
            let memberIds = group.memberEntryIds.filter { visibleIds.contains($0) }
            let targetReplacement: ModelEntry?
            if let role = roleEntry, !memberIds.contains(role.id) {
                targetReplacement = role
            } else if let office = officeEntry, !memberIds.contains(office.id) {
                targetReplacement = office
            } else {
                targetReplacement = nil
            }
            let migratedMemberIds: [String]
            if let coding = codingEntry, let rep = targetReplacement, memberIds.contains(coding.id) {
                migratedMemberIds = memberIds.map { $0 == coding.id ? rep.id : $0 }
            } else {
                migratedMemberIds = memberIds
            }
            return Array(migratedMemberIds.prefix(3))
        }
        return GatewaySync.defaultGatewayTierEntryIds(from: gatewayEntries)
    }

    private func createGroupAndDismiss() {
        let group = ModelGroup(
            name: "Default Models",
            memberEntryIds: selectedModelEntryIds,
            strategy: .fallback
        )
        store.addGroup(group)
        if store.defaultPrimaryGroupId == nil {
            store.defaultPrimaryGroupId = group.id
        }
        dismiss()
    }
}
