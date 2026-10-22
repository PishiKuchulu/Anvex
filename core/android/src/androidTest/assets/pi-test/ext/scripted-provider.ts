/**
 * Scripted fake provider for Pi engine tests — deterministic, no network,
 * no API key. Scenario (branches on which tool results exist so far):
 *   A: write poc-out/hello.txt                     (host policy: SAFE file write)
 *   B: bash compound with &&                       (host policy: needs approval -> test DENIES)
 *   C: bash single echo-redirect                   (host policy: SAFE -> runs)
 *   D: final text
 * The stream class comes from the vendored leaf module next to this file
 * in the test harness layout (vendor/event-stream.js).
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

function toolResults(context: { messages: Msg[] }): string[] {
	const names: string[] = [];
	for (const m of context.messages) if (m.role === "toolResult") names.push(String(m.toolName ?? "?"));
	return names;
}

function streamScripted(model: Msg, context: { messages: Msg[] }, options?: Msg) {
	const stream = createAssistantMessageEventStream();
	(async () => {
		const output: Msg = {
			role: "assistant", content: [], api: model.api, provider: model.provider,
			model: model.id, usage: emptyUsage(), stopReason: "stop", timestamp: Date.now(),
		};
		try {
			await (options as any)?.onPayload?.({ model: model.id, scripted: true });
			stream.push({ type: "start", partial: output });
			const seen = toolResults(context);
			const bashCount = seen.filter((n) => n === "bash").length;
			let call: { id: string; name: string; args: Record<string, unknown> } | null = null;
			if (!seen.includes("write")) {
				call = { id: "sc-write", name: "write", args: { path: "poc-out/hello.txt", content: "hello from pi poc\n" } };
			} else if (bashCount === 0) {
				call = { id: "sc-bash-deny", name: "bash", args: { command: "echo x > poc-out/compound.txt && echo y > poc-out/compound2.txt" } };
			} else if (bashCount === 1) {
				call = { id: "sc-bash-safe", name: "bash", args: { command: "echo approved > poc-out/approved.txt" } };
			}
			if (call) {
				const block = { type: "toolCall", id: call.id, name: call.name, arguments: call.args };
				(output.content as unknown[]).push(block);
				const idx = (output.content as unknown[]).length - 1;
				stream.push({ type: "toolcall_start", contentIndex: idx, partial: output });
				stream.push({ type: "toolcall_delta", contentIndex: idx, delta: JSON.stringify(call.args), partial: output });
				stream.push({ type: "toolcall_end", contentIndex: idx, toolCall: block, partial: output });
				output.stopReason = "toolUse";
			} else {
				const text = "Scripted task complete.";
				const block = { type: "text", text };
				(output.content as unknown[]).push(block);
				const idx = (output.content as unknown[]).length - 1;
				stream.push({ type: "text_start", contentIndex: idx, partial: output });
				stream.push({ type: "text_delta", contentIndex: idx, delta: text, partial: output });
				stream.push({ type: "text_end", contentIndex: idx, content: text, partial: output });
			}
			await (options as any)?.onResponse?.({ status: 200, headers: {} });
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
		name: "POC scripted provider",
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
