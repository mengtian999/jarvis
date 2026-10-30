import XCTest
@testable import Minis

/// 单池后 /v1/models 只返回扁平 data[]（无 pools / region）。
/// 锁定解析行为：按 kind 分流、同 id 去重、kind 缺省视为 chat、忽略旧 pools。
final class GatewaySyncModelsTests: XCTestCase {

    func testPicksChatImageVideoFromFlatData() {
        let json: [String: Any] = [
            "object": "list",
            "data": [
                ["id": "auto", "kind": "chat", "display_name": "Jarvis (Default)", "is_default": true],
                ["id": "image", "kind": "image", "display_name": "Images"],
                ["id": "video", "kind": "video", "display_name": "Videos"],
            ],
        ]

        XCTAssertEqual(GatewaySync.pickModels(json, kind: "chat").map(\.id), ["auto"])
        XCTAssertEqual(GatewaySync.pickModels(json, kind: "image").map(\.id), ["image"])
        XCTAssertEqual(GatewaySync.pickModels(json, kind: "video").map(\.id), ["video"])
        XCTAssertEqual(GatewaySync.pickModels(json, kind: "chat").first?.isDefault, true)
    }

    func testMissingKindTreatedAsChat() {
        let json: [String: Any] = [
            "data": [["id": "auto", "display_name": "A"]],
        ]

        XCTAssertEqual(GatewaySync.pickModels(json, kind: "chat").map(\.id), ["auto"])
        XCTAssertTrue(GatewaySync.pickModels(json, kind: "image").isEmpty)
        XCTAssertTrue(GatewaySync.pickModels(json, kind: "video").isEmpty)
    }

    func testDeduplicatesRepeatedIds() {
        let json: [String: Any] = [
            "data": [
                ["id": "auto", "kind": "chat"],
                ["id": "auto", "kind": "chat"],
                ["id": "office", "kind": "chat"],
            ],
        ]

        XCTAssertEqual(GatewaySync.pickModels(json, kind: "chat").map(\.id), ["auto", "office"])
    }

    func testIgnoresLegacyPoolsKey() {
        let json: [String: Any] = [
            "pools": ["cn": ["chat": [["id": "legacy-auto"]]]],
            "data": [["id": "office", "kind": "chat"]],
        ]

        // 只读 data[]，不再读 pools
        XCTAssertEqual(GatewaySync.pickModels(json, kind: "chat").map(\.id), ["office"])
    }

    func testParsesContextBudget() {
        let json: [String: Any] = [
            "data": [
                ["id": "auto", "kind": "chat", "context_window": 200000, "max_output_tokens": 32000],
                ["id": "office", "kind": "chat"],
            ],
        ]

        let tiers = GatewaySync.pickModels(json, kind: "chat")
        let auto = tiers.first { $0.id == "auto" }
        let office = tiers.first { $0.id == "office" }

        XCTAssertEqual(auto?.contextWindow, 200000)
        XCTAssertEqual(auto?.maxOutputTokens, 32000)
        // 未声明 → nil，由 LLMModel 侧兜底
        XCTAssertNil(office?.contextWindow)
        XCTAssertNil(office?.maxOutputTokens)
    }

    func testEmptyWhenDataMissing() {
        XCTAssertTrue(GatewaySync.pickModels(["object": "list"], kind: "chat").isEmpty)
    }
}