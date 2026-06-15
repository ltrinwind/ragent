import * as React from "react";
import { Brain, ChevronDown, FileText, ImageIcon } from "lucide-react";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { cn } from "@/lib/utils";
import type { Message } from "@/types";

/** 图片代理端点的前端基础地址，与知识库文档预览（KnowledgeDocumentsPage）拼法一致 */
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
}

export const MessageItem = React.memo(function MessageItem({ message, isLast }: MessageItemProps) {
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const isThinking = Boolean(message.isThinking);
  const [thinkingExpanded, setThinkingExpanded] = React.useState(false);
  const [contextsExpanded, setContextsExpanded] = React.useState(false);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const hasContexts = Boolean(message.contexts && message.contexts.length > 0);
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !isThinking && !hasContent;

  if (isUser) {
    return (
      <div className="flex">
        <div className="user-message">
          <p className="whitespace-pre-wrap break-words">{message.content}</p>
        </div>
      </div>
    );
  }

  const thinkingDuration = message.thinkingDuration ? `${message.thinkingDuration}秒` : "";
  return (
    <div className="group flex">
      <div className="min-w-0 flex-1 space-y-4">
        {isThinking ? (
          <ThinkingIndicator content={message.thinking} duration={message.thinkingDuration} />
        ) : null}
        {!isThinking && hasThinking ? (
          <div className="overflow-hidden rounded-lg border border-[#BFDBFE] bg-[#DBEAFE]">
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[#BFDBFE]/30"
            >
              <div className="flex flex-1 items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#BFDBFE]">
                  <Brain className="h-4 w-4 text-[#2563EB]" />
                </div>
                <span className="text-sm font-medium text-[#2563EB]">深度思考</span>
                {thinkingDuration ? (
                  <span className="rounded-full bg-[#BFDBFE] px-2 py-0.5 text-xs text-[#2563EB]">
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-[#3B82F6] transition-transform",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div className="border-t border-[#BFDBFE] px-4 pb-4">
                <div className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-[#1E40AF]">
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
        <div className="space-y-2">
          {isWaiting ? (
            <div className="ai-wait" aria-label="思考中">
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {hasContent ? <MarkdownRenderer content={message.content} /> : null}
          {message.status === "error" ? (
            <p className="text-xs text-rose-500">生成已中断。</p>
          ) : null}
          {hasContexts ? (
            <div className="overflow-hidden rounded-lg border border-[#BFDBFE] bg-[#DBEAFE]">
              <button
                type="button"
                onClick={() => setContextsExpanded((prev) => !prev)}
                className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[#BFDBFE]/30"
              >
                <div className="flex flex-1 items-center gap-2">
                  <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#BFDBFE]">
                    <FileText className="h-4 w-4 text-[#2563EB]" />
                  </div>
                  <span className="text-sm font-medium text-[#2563EB]">参考来源</span>
                  <span className="rounded-full bg-[#BFDBFE] px-2 py-0.5 text-xs text-[#2563EB]">
                    {message.contexts!.length}
                  </span>
                </div>
                <ChevronDown
                  className={cn(
                    "h-4 w-4 text-[#3B82F6] transition-transform",
                    contextsExpanded && "rotate-180"
                  )}
                />
              </button>
              {contextsExpanded ? (
                <div className="border-t border-[#BFDBFE] px-4 pb-4">
                  <div className="mt-3 space-y-3">
                    {message.contexts!.map((ctx, idx) => (
                      <div key={idx} className="rounded-md bg-white/60 p-3">
                        <span className="mb-1 block text-xs font-medium text-[#2563EB]">
                          #{idx + 1}
                        </span>
                        {ctx.contentType === "IMAGE" && ctx.imageUrl ? (
                          <div className="space-y-2">
                            <span className="inline-flex items-center gap-1 rounded bg-[#BFDBFE] px-1.5 py-0.5 text-[11px] text-[#2563EB]">
                              <ImageIcon className="h-3 w-3" />
                              图片
                            </span>
                            <img
                              src={`${API_BASE_URL}${ctx.imageUrl}`}
                              alt={ctx.text || "参考图片"}
                              className="max-w-full rounded-lg"
                              loading="lazy"
                            />
                            {ctx.text ? (
                              <p className="whitespace-pre-wrap text-sm leading-relaxed text-[#1E40AF]">
                                {ctx.text}
                              </p>
                            ) : null}
                          </div>
                        ) : (
                          <p className="whitespace-pre-wrap text-sm leading-relaxed text-[#1E40AF]">
                            {ctx.text}
                          </p>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}
          {showFeedback ? (
            <FeedbackButtons
              messageId={message.id}
              feedback={message.feedback ?? null}
              content={message.content}
              alwaysVisible={Boolean(isLast)}
            />
          ) : null}
        </div>
      </div>
    </div>
  );
});
