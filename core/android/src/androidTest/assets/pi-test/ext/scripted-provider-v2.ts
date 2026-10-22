/**
 * Scripted provider v2 — proves Pi SESSION CONTINUITY through the chat
 * bridge: the final text reports how many user messages exist in the
 * transcript the provider can see ("history-users=N"). In a resumed
 * session N grows across runs; in a fresh session it stays 1.
 * Turn shape: one safe write (turn marker), then the report text.
 */
// @ts-ignore - vendored JS leaf module, path set by the harness layout
import { createAssistantMessageEventStream } from "../vendor/event-stream.js";

type Msg = Record<string, unknown>;

function emptyUsage() {
	return {
		input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};
}

function streamScripted(model: Msg, context: { messages: Msg[] }, options?: Msg) {
	const stream = createAssistantMessageEventStream();
	(async () => {
		const output: Msg = {
			role: "assistant", content: [], api: model.api, provider: model.provider,
			model: model.id, usage: emptyUsage(), stopReason: "stop", timestamp: Date.now(),
		};
		try {
			stream.push({ type: "start", partial: output });
			const users = context.messages.filter((m) => m.role === "user").length;
			const hasToolResult = context.messages.some((m) => m.role === "toolResult");
			if (!hasToolResult) {
				const block = { type: "toolCall", id: "v2-write", name: "write", arguments: { path: "turn-marker.txt", content: "turn\n" } };
				(output.content as unknown[]).push(block);
				stream.push({ type: "toolcall_start", contentIndex: 0, partial: output });
				stream.push({ type: "toolcall_delta", contentIndex: 0, delta: JSON.stringify(block.arguments), partial: output });
				stream.push({ type: "toolcall_end", contentIndex: 0, toolCall: block, partial: output });
				output.stopReason = "toolUse";
			} else {
				const text = `history-users=${users}`;
				const block = { type: "text", text };
				(output.content as unknown[]).push(block);
				stream.push({ type: "text_start", contentIndex: 0, partial: output });
				stream.push({ type: "text_delta", contentIndex: 0, delta: text, partial: output });
				stream.push({ type: "text_end", contentIndex: 0, content: text, partial: output });
			}
			stream.push({ type: "done", reason: output.stopReason, message: output });
			stream.end();
		} catch (err) {
			stream.push({ type: "error", reason: "error", error: err instanceof Error ? err : new Error(String(err)) });
			stream.end();
		}
	})();
	return stream;
}

export default function (pi: { registerProvider: (name: string, config: Record<string, unknown>) => void }) {
	pi.registerProvider("poc", {
		name: "POC scripted provider v2",
		baseUrl: "http://127.0.0.1:9/unused",
		apiKey: "poc-scripted-no-key",
		api: "poc-scripted-api",
		models: [
			{
				id: "poc-scripted", name: "POC Scripted", api: "poc-scripted-api",
				reasoning: false, input: ["text"],
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				contextWindow: 32768, maxTokens: 4096,
			},
		],
		streamSimple: streamScripted,
	});
}
