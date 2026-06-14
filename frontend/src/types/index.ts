export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  thinking?: string;
  thinkingDuration?: number;
  isDeepThinking?: boolean;
  isThinking?: boolean;
  contexts?: RetrievedContextItem[];
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
}

export interface RetrievedContextItem {
  /** 命中 chunk 的唯一标识（分布式 ID） */
  id: string;
  /** 文本内容；IMAGE 类型时为图片描述 */
  text: string;
  /** 内容类型：TEXT 或 IMAGE */
  contentType: string;
  /** 图片代理路径（仅 IMAGE），前端拼接 API_BASE 后请求 */
  imageUrl?: string;
  /** 相关性得分（预留） */
  score?: number;
}

export interface ContextPayload {
  contexts: RetrievedContextItem[];
}
