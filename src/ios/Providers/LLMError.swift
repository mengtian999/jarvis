import Foundation

enum LLMError: LocalizedError {
    case invalidAPIKey(detail: String = "")
    case networkError(underlying: Error)
    case providerError(message: String)
    /// Transient server-side errors (HTTP 500/502/503/504/529) that should be
    /// retried on the same model rather than triggering a group fallback.
    case transientError(message: String)
    case decodingError(underlying: Error)
    case rateLimited
    /// [T-gateway-quota-options] Gateway quota/budget exhaustion (方案 §1.4):
    /// carries the server's human-readable message plus the recovery `options`
    /// payload (明日再来 / 登录提额 / BYOK) so the chat UI can render the choices.
    /// Deliberately NOT retryable and NOT fallbackable: silently falling through
    /// to the next group member would burn the next tier's quota and defeat the
    /// §2.4 差异化限额 cost funnel. Mirrors Android LLMError.QuotaExceeded.
    case quotaExceeded(message: String, options: [QuotaOption])
    case cancelled
    case unknown(underlying: Error?)

    var errorDescription: String? {
        switch self {
        case .invalidAPIKey(let detail):
            return detail.isEmpty ? "Invalid API key" : "Invalid API key: \(detail)"
        case .networkError(let error):
            return "Network error: \(error.localizedDescription)"
        case .providerError(let message):
            return "Provider error: \(message)"
        case .transientError(let message):
            return "Service temporarily unavailable: \(message)"
        case .decodingError(let error):
            return "Decoding error: \(error.localizedDescription)"
        case .rateLimited:
            return "Rate limited — please try again later"
        case .quotaExceeded(let message, _):
            return message
        case .cancelled:
            return "Request was cancelled"
        case .unknown(let error):
            return "Unknown error: \(error?.localizedDescription ?? "no details")"
        }
    }

    var isNetworkError: Bool {
        if case .networkError = self { return true }
        return false
    }

    /// Errors that should be retried with countdown on the same provider.
    /// Includes both network errors and transient server-side errors (5xx).
    var isRetryable: Bool {
        switch self {
        case .networkError, .transientError:
            return true
        case .invalidAPIKey, .providerError, .decodingError, .rateLimited, .quotaExceeded, .cancelled, .unknown:
            return false
        }
    }

    /// Errors that indicate the provider itself cannot serve this request
    /// (rate limit, invalid key, permanent provider-side rejection). These trigger
    /// an immediate fallback to the next model in a group, without retry countdown.
    ///
    /// Note: transientError and networkError are also fallbackable — after
    /// auto-retry is exhausted on the current model, group fallback kicks in.
    var fallbackReason: String {
        switch self {
        case .rateLimited: return "Rate limited"
        case .quotaExceeded: return "Quota exceeded"
        case .invalidAPIKey: return "Invalid API key"
        case .providerError(let msg): return "Provider error: \(String(msg.prefix(60)))"
        default: return "Error"
        }
    }

    var isFallbackable: Bool {
        switch self {
        case .rateLimited, .invalidAPIKey, .providerError:
            return true
        case .transientError, .networkError, .decodingError, .quotaExceeded, .cancelled, .unknown:
            return false
        }
    }
}

/// [T-gateway-quota-options] One entry of the gateway's quota-error `options`
/// array (§1.4 recovery choices: tomorrow / login / byok).
struct QuotaOption: Equatable {
    let key: String
    let label: String
}

/// [T-gateway-quota-options] Alert payload the chat view renders when the
/// gateway refuses with a quota/budget error. Identifiable for `.alert(item:)`.
struct QuotaAlertPayload: Identifiable, Equatable {
    let id = UUID()
    let message: String
    let options: [QuotaOption]
}
