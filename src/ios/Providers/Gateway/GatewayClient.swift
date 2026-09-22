import Foundation
import CryptoKit
import Security
import UIKit
import os.log

// MARK: - GatewayIdentity

/// Anonymous device identity for the Jarvis model gateway (技术方案 §1.2).
/// Persisted in Keychain — survives app reinstalls. installId is derived from
/// `DeviceIdentity.deviceId` (the same stable UUID used for iCloud sync zones),
/// so the gateway's quota record follows the device across token rotations.
struct GatewayIdentity: Codable, Sendable {
    let installId: String
    let deviceId: String
    let token: String
    /// "cn" | "intl" — server-assigned based on client IP (§4.2 线路选择).
    let region: String
    let registeredAt: Date
}

// MARK: - GatewayError

enum GatewayError: LocalizedError {
    case notRegistered
    case registrationFailed(status: Int, message: String)
    case requestFailed(status: Int, message: String)
    case parseFailed(String)

    var errorDescription: String? {
        switch self {
        case .notRegistered:
            return "Gateway device not registered"
        case .registrationFailed(let s, let m):
            return "Gateway registration failed (\(s)): \(m)"
        case .requestFailed(let s, let m):
            return "Gateway request failed (\(s)): \(m)"
        case .parseFailed(let m):
            return "Gateway response parse failed: \(m)"
        }
    }
}

// MARK: - GatewayClient

/// Jarvis model gateway client — Route A (real HMAC-signed requests).
///
/// Manages:
///   - Anonymous device registration (install_id idempotent, §1.2)
///   - HMAC-SHA256 request signing (X-Timestamp / X-Signature, §1.3)
///   - Gateway API calls: /v1/models, /v1/quota
///
/// Signing scheme (§1.3):
///   X-Timestamp:  unix seconds
///   X-Signature:  hex(HMAC_SHA256(appSecret, timestamp + "\n" + body))
///
/// Device auth:
///   Authorization: Bearer <device_token>
///
/// The appSecret is embedded in the client binary — it can be reverse-engineered
/// and only raises the bar. The real defense is server-side quota/rate-limit (§1.3).
/// Mirrors desktop `core/gateway/gateway-client.ts`.
final class GatewayClient: @unchecked Sendable {
    static let shared = GatewayClient()

    private let logger = AppLogger(category: "GatewayClient")

    /// Production gateway base URL (no trailing slash). Overridable for testing.
    let baseURL: String
    /// HMAC-SHA256 signing secret (shared with server config, §1.3).
    private let appSecret: String
    /// Platform identifier sent to register ("ios").
    private let platform: String
    /// App marketing version sent to register.
    private let appVersion: String
    /// Dedicated URLSession (30s timeout; gateway calls are quick).
    private let session: URLSession

    /// Keychain service/account for persisting the device identity JSON.
    private static let kcService = "com.jarvis.app.gateway"
    private static let kcAccount = "device-identity"

    // MARK: - Init

    init(baseURL: String? = nil, appSecret: String? = nil,
         session: URLSession? = nil) {
        var url = baseURL ?? "https://gateway.bitjarvis.chat"
        while url.hasSuffix("/") { url = String(url.dropLast()) }
        self.baseURL = url
        self.appSecret = appSecret
            ?? "b0d759b1b38ee762be677d5ff0159b16489da3c70ec6780d96ededb50cd98870"
        self.platform = "ios"
        self.appVersion = (Bundle.main.infoDictionary?["CFBundleShortVersionString"]
            as? String) ?? "0.0.1"
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 30
            config.timeoutIntervalForResource = 60
            config.httpMaximumConnectionsPerHost = 2
            self.session = URLSession(configuration: config)
        }
    }

    // MARK: - Identity

    /// Current device identity (loaded from Keychain; nil if not registered).
    var identity: GatewayIdentity? { loadIdentity() }

    /// OpenAI-compatible chat base URL — `<baseURL>/v1`.
    var chatBaseURL: String { "\(baseURL)/v1" }

    /// Ensure device is registered. Returns cached identity or registers new.
    func ensureDevice() async throws -> GatewayIdentity {
        if let cached = loadIdentity() { return cached }
        return try await register()
    }

    // MARK: - Register (§1.2)

    /// Register device with gateway (POST /v1/devices/register).
    /// install_id is idempotent — duplicate registrations rotate the token;
    /// the quota record follows the install_id, so no quota is lost.
    func register() async throws -> GatewayIdentity {
        let installId = DeviceIdentity.deviceId
        let body: [String: String] = [
            "install_id": installId,
            "platform": platform,
            "app_version": appVersion,
            "device_info_hash": deviceInfoHash(),
        ]
        let bodyData = try JSONSerialization.data(withJSONObject: body)

        var request = URLRequest(url: URL(string: "\(baseURL)/v1/devices/register")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = bodyData
        sign(&request) // Route A: real signing

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw GatewayError.requestFailed(status: 0, message: "No HTTP response")
        }
        guard (200 ... 299).contains(http.statusCode) else {
            let msg = parseErrorMessage(data) ?? "HTTP \(http.statusCode)"
            throw GatewayError.registrationFailed(status: http.statusCode, message: msg)
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GatewayError.parseFailed("Invalid JSON in register response")
        }
        let deviceId = json["device_id"] as? String ?? ""
        let token = json["token"] as? String ?? ""
        guard !token.isEmpty, !deviceId.isEmpty else {
            throw GatewayError.parseFailed("Missing device_id or token in register response")
        }
        let identity = GatewayIdentity(
            installId: installId,
            deviceId: deviceId,
            token: token,
            region: (json["region"] as? String) ?? "cn",
            registeredAt: Date()
        )
        saveIdentity(identity)
        logger.info("Registered: deviceId=\(identity.deviceId) region=\(identity.region)")
        return identity
    }

    // MARK: - List Models (§2.1)

    /// Fetch model list (GET /v1/models). Returns the raw parsed JSON.
    /// Response shape: { object: "list", data: [...], pools: { region: { chat, image, video } } }
    func listModels() async throws -> [String: Any] {
        let id = try await ensureDevice()
        var request = URLRequest(url: URL(string: "\(baseURL)/v1/models")!)
        request.httpMethod = "GET"
        request.setValue("Bearer \(id.token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        sign(&request)

        let (data, _) = try await send(request)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GatewayError.parseFailed("Invalid JSON in models response")
        }
        return json
    }

    // MARK: - Get Quota

    /// Fetch device quota (GET /v1/quota). Returns the raw parsed JSON.
    func getQuota() async throws -> [String: Any] {
        let id = try await ensureDevice()
        var request = URLRequest(url: URL(string: "\(baseURL)/v1/quota")!)
        request.httpMethod = "GET"
        request.setValue("Bearer \(id.token)", forHTTPHeaderField: "Authorization")
        sign(&request)

        let (data, _) = try await send(request)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GatewayError.parseFailed("Invalid JSON in quota response")
        }
        return json
    }

    // MARK: - Signing (Route A, §1.3)

    /// Sign a URLRequest with HMAC-SHA256.
    ///
    /// X-Timestamp:  unix seconds (string)
    /// X-Signature:  hex(HMAC_SHA256(appSecret, timestamp + "\n" + body_bytes))
    ///
    /// For GET requests (no body), body is empty — the signature covers
    /// just `timestamp + "\n"`. The gateway's middleware reads `r.Body`
    /// (empty for GET) and computes the same HMAC.
    func sign(_ request: inout URLRequest) {
        let ts = String(Int(Date().timeIntervalSince1970))
        let body = request.httpBody ?? Data()

        // Message = timestamp + "\n" + body (matches Go:
        // mac.Write(ts); mac.Write("\n"); mac.Write(body))
        var message = Data()
        message.append(contentsOf: ts.utf8)
        message.append(0x0a) // "\n"
        message.append(body)

        let key = SymmetricKey(data: Data(appSecret.utf8))
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: key)
        let hex = mac.map { String(format: "%02x", $0) }.joined()

        request.setValue(ts, forHTTPHeaderField: "X-Timestamp")
        request.setValue(hex, forHTTPHeaderField: "X-Signature")
    }

    // MARK: - Private Helpers

    /// Send a signed request, handling 401 (token expired) by clearing identity.
    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw GatewayError.requestFailed(status: 0, message: "No HTTP response")
        }
        if http.statusCode == 401 {
            // Token invalid/expired — clear so next ensureDevice() re-registers.
            clearIdentity()
            throw GatewayError.requestFailed(status: 401, message: "Token invalid — re-registration required")
        }
        guard (200 ... 299).contains(http.statusCode) else {
            let msg = parseErrorMessage(data) ?? "HTTP \(http.statusCode)"
            throw GatewayError.requestFailed(status: http.statusCode, message: msg)
        }
        return (data, http)
    }

    /// Device info hash (§1.2): stable, irreversible, no PII.
    /// SHA256(platform | model | "gateway-v1"), first 32 hex chars.
    private func deviceInfoHash() -> String {
        let model = UIDevice.current.model // e.g. "iPhone" — not PII
        let info = "\(platform)|\(model)|gateway-v1"
        let digest = SHA256.hash(data: Data(info.utf8))
        return String(digest.map { String(format: "%02x", $0) }.joined().prefix(32))
    }

    private func parseErrorMessage(_ data: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let error = json["error"] as? [String: Any] {
            return (error["message"] as? String) ?? (error["code"] as? String)
        }
        return (json["message"] as? String) ?? (json["error"] as? String)
    }

    // MARK: - Keychain Persistence

    private func loadIdentity() -> GatewayIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.kcService,
            kSecAttrAccount as String: Self.kcAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(GatewayIdentity.self, from: data)
    }

    private func saveIdentity(_ identity: GatewayIdentity) {
        guard let data = try? JSONEncoder().encode(identity) else { return }
        let baseQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.kcService,
            kSecAttrAccount as String: Self.kcAccount,
        ]
        SecItemDelete(baseQuery as CFDictionary) // idempotent overwrite
        var addQuery = baseQuery
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(addQuery as CFDictionary, nil)
    }

    /// Clear the stored identity (called on 401 token_invalid, or by the user
    /// from settings to force re-registration).
    func clearIdentity() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.kcService,
            kSecAttrAccount as String: Self.kcAccount,
        ]
        SecItemDelete(query as CFDictionary)
        logger.info("Identity cleared")
    }
}
