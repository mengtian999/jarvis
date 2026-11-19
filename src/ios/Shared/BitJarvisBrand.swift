import Foundation

/// Bit Jarvis product branding & public URLs used by Settings / About / ASC.
enum BitJarvisBrand {
    static let displayName = "Bit Jarvis"
    static let legalEntity = "宣城市比特科技有限公司"
    static let supportEmail = "bit@bitjarvis.chat"

    static let websiteURL = URL(string: "https://www.bitjarvis.chat")!
    static let privacyPolicyURL = URL(string: "https://www.bitjarvis.chat/privacy.html")!
    /// Canonical copy lives in-repo (`docs/legal/terms-of-use.html`). Prefer the
    /// site path once deployed; jsDelivr mirrors GitHub `main` as a stable HTTPS URL.
    static let termsOfUseURL = URL(string: "https://cdn.jsdelivr.net/gh/mengtian999/jarvis@main/docs/legal/terms-of-use.html")!
    static let feedbackIMURL = URL(string: "https://www.bitjarvis.chat/invite.html?id=!r5PjhfZnf2BsDxysbJ:bitjarvis.chat")!

    /// One-line positioning: utility / on-device agent tool (BYOK), not an AIGC content service.
    static let taglineEN = "Your fully local, private on-device agent toolkit."
    static let taglineZH = "完全本地、隐私优先的端侧 Agent 工具。"
}
