//
//  LiquidGlassCompat.swift
//  Minis
//
//  Xcode 16.2 / iOS 18.2 SDK shims for Liquid Glass APIs introduced with
//  iOS 26. Real SwiftUI symbols win automatically once the project is built
//  with an SDK that ships AlarmKit (same train as Liquid Glass).
//

import SwiftUI

#if !canImport(AlarmKit)

/// Stand-in for SwiftUI.Glass when the SDK does not yet provide it.
struct Glass: Equatable {
    static let regular = Glass()

    func tint(_ color: Color) -> Glass { self }
}

extension View {
    /// No-op stand-in for `View.glassEffect(_:in:)`.
    @ViewBuilder
    func glassEffect(
        _ glass: Glass = .regular,
        in shape: some Shape
    ) -> some View {
        self
    }

    /// No-op stand-in for `View.glassEffectID(_:in:)`.
    func glassEffectID(
        _ id: some Hashable,
        in namespace: Namespace.ID
    ) -> some View {
        self
    }
}

#endif
