import {
  type CSSProperties,
  type FormEvent,
  type ReactNode,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  ArrowLeftIcon,
  BellIcon,
  BookmarkIcon,
  ChatBubbleIcon,
  CheckIcon,
  ChevronRightIcon,
  Cross2Icon,
  DotsHorizontalIcon,
  EnvelopeClosedIcon,
  ExitIcon,
  EyeClosedIcon,
  EyeOpenIcon,
  FaceIcon,
  FileIcon,
  GearIcon,
  ImageIcon,
  InfoCircledIcon,
  LockClosedIcon,
  MagnifyingGlassIcon,
  PaperPlaneIcon,
  PersonIcon,
  PlusIcon,
  ReloadIcon,
  SpeakerOffIcon,
} from "@radix-ui/react-icons";
import {
  FlowStack,
  KeyboardInput,
  KeyboardTextarea,
  MobileScroll,
  type FlowControls,
  type FlowScreen,
  useKeyboard,
  useKeyboardInsets,
} from "./mobile";
import {
  AuthApiError,
  clearSession,
  demoModeEnabled,
  loadSession,
  loginWithCode,
  loginWithPassword,
  logout,
  refreshSession,
  registerAccount,
  saveSession,
  sendCaptcha,
  type AuthSession,
} from "./auth";
import {
  ContactApiError,
  fetchFriendApplicationCount,
  fetchFriendApplications,
  fetchFriendDetail,
  fetchFriends,
  searchUser,
  sendFriendRequest,
  updateFriendApplications,
  type ApplicationDecision,
  type FriendApplication,
  type FriendProfile,
} from "./contacts";
import {
  GroupApiError,
  createGroup,
  inviteGroupMembers,
  type CreateGroupResult,
  type InviteGroupResult,
} from "./groups";
import {
  MediaApiError,
  prepareChatImage,
  uploadChatImage,
} from "./media";
import {
  ChatRealtimeClient,
  RealtimeApiError,
  clearOfflineCursor,
  fetchHistoryMessages,
  fetchMessageStatus,
  loadOfflineCursor,
  saveOfflineCursor,
  syncOfflineMessages,
  type OfflineSyncResponse,
  type OutgoingRealtimeMessage,
  type RealtimeConnectionState,
  type RealtimeMessage,
  type RealtimeMessageAck,
} from "./realtime";
import {
  fetchSessionList,
  markSessionRead,
  type SessionSummary,
} from "./sessions";

type Phase = "booting" | "signed-out" | "signed-in";
type LoginMode = "password" | "code";

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function Prototype() {
  useKeepFocusedAuthFieldVisible();
  const [phase, setPhase] = useState<Phase>("booting");
  const [session, setSession] = useState<AuthSession | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(async () => {
      const storedSession = loadSession();
      if (!storedSession) {
        setPhase("signed-out");
        return;
      }

      if (demoModeEnabled) {
        setSession(storedSession);
        setPhase("signed-in");
        return;
      }

      try {
        const renewedTokens = await refreshSession(storedSession.refreshToken);
        const renewedSession = { ...storedSession, ...renewedTokens };
        saveSession(renewedSession);
        setSession(renewedSession);
        setPhase("signed-in");
      } catch {
        clearSession();
        setSession(null);
        setPhase("signed-out");
      }
    }, 720);

    return () => window.clearTimeout(timer);
  }, []);

  const onAuthenticated = (nextSession: AuthSession) => {
    saveSession(nextSession);
    setSession(nextSession);
    setPhase("signed-in");
  };

  const onLogout = async () => {
    const accessToken = session?.accessToken;
    clearSession();
    setSession(null);
    setPhase("signed-out");
    if (accessToken) {
      try {
        await logout(accessToken);
      } catch {
        // The local session is already cleared; a gateway outage must not trap the user.
      }
    }
  };

  if (phase === "booting") return <SplashScreen />;

  if (phase === "signed-in" && session) {
    return <MainShell session={session} onLogout={onLogout} />;
  }

  return <FlowStack initial={loginFlowScreen(onAuthenticated)} />;
}

function useKeepFocusedAuthFieldVisible() {
  useEffect(() => {
    let revealTimer: number | undefined;

    const handleFocus = (event: FocusEvent) => {
      const target = event.target;
      if (!(target instanceof HTMLInputElement) || !target.closest(".auth-field")) return;

      if (revealTimer) window.clearTimeout(revealTimer);
      revealTimer = window.setTimeout(() => {
        const field = target.closest<HTMLElement>(".auth-field");
        const scroll = target.closest<HTMLElement>(".mobile-scroll");
        if (!field || !scroll) return;

        const fieldRect = field.getBoundingClientRect();
        const scrollRect = scroll.getBoundingClientRect();
        const topLimit = scrollRect.top + 14;
        const bottomLimit = scrollRect.bottom - 18;
        const delta =
          fieldRect.bottom > bottomLimit
            ? fieldRect.bottom - bottomLimit
            : fieldRect.top < topLimit
              ? fieldRect.top - topLimit
              : 0;

        if (Math.abs(delta) > 1) {
          const viewportScale = scroll.offsetHeight > 0 ? scrollRect.height / scroll.offsetHeight : 1;
          scroll.scrollBy({ top: delta / Math.max(viewportScale, 0.1), behavior: "smooth" });
        }
      }, 300);
    };

    document.addEventListener("focusin", handleFocus);
    return () => {
      document.removeEventListener("focusin", handleFocus);
      if (revealTimer) window.clearTimeout(revealTimer);
    };
  }, []);
}

function SplashScreen() {
  return (
    <div className="splash-screen" aria-label="ChatIM 正在启动">
      <div className="splash-mark" aria-hidden="true">
        C
      </div>
      <strong>ChatIM</strong>
      <span>让每一次连接，都温柔发生</span>
    </div>
  );
}

function loginFlowScreen(onAuthenticated: (session: AuthSession) => void): FlowScreen {
  return {
    id: "login",
    render: (flow) => <LoginScreen flow={flow} onAuthenticated={onAuthenticated} />,
  };
}

function registerFlowScreen(onAuthenticated: (session: AuthSession) => void): FlowScreen {
  return {
    id: "register",
    headerHeight: 58,
    header: (flow) => (
      <div className="register-fixed-header">
        <button type="button" className="icon-button" onClick={flow.pop} aria-label="返回登录">
          <ArrowLeftIcon />
        </button>
        <strong>创建账号</strong>
        <span aria-hidden="true" />
      </div>
    ),
    render: () => <RegisterScreen onAuthenticated={onAuthenticated} />,
  };
}

function LoginScreen({
  flow,
  onAuthenticated,
}: {
  flow: FlowControls;
  onAuthenticated: (session: AuthSession) => void;
}) {
  const keyboard = useKeyboard();
  const [mode, setMode] = useState<LoginMode>("password");
  const [email, setEmail] = useState(demoModeEnabled ? "demo@chatim.cn" : "");
  const [password, setPassword] = useState(demoModeEnabled ? "123456" : "");
  const [code, setCode] = useState(demoModeEnabled ? "123456" : "");
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [message, setMessage] = useState("");
  const [messageTone, setMessageTone] = useState<"error" | "success">("error");

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown((value) => value - 1), 1_000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  const switchMode = (nextMode: LoginMode) => {
    keyboard.hide();
    setMode(nextMode);
    setMessage("");
  };

  const showError = (value: string) => {
    setMessageTone("error");
    setMessage(value);
  };

  const handleCaptcha = async () => {
    keyboard.hide();
    if (!emailPattern.test(email.trim())) {
      showError("请先填写正确的邮箱地址");
      return;
    }

    setSendingCode(true);
    setMessage("");
    try {
      await sendCaptcha(email.trim());
      setCountdown(60);
      setMessageTone("success");
      setMessage(demoModeEnabled ? "验证码已发送，演示验证码是 123456" : "验证码已发送，请留意邮箱");
    } catch (error) {
      showError(toErrorMessage(error));
    } finally {
      setSendingCode(false);
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    keyboard.hide();
    const normalizedEmail = email.trim();

    if (!emailPattern.test(normalizedEmail)) {
      showError("请输入正确的邮箱地址");
      return;
    }
    if (mode === "password" && (password.length < 6 || password.length > 20)) {
      showError("密码长度需要为 6–20 位");
      return;
    }
    if (mode === "code" && !/^\d{6}$/.test(code)) {
      showError("请输入 6 位数字验证码");
      return;
    }

    setBusy(true);
    setMessage("");
    try {
      const nextSession =
        mode === "password"
          ? await loginWithPassword({ email: normalizedEmail, password })
          : await loginWithCode({ email: normalizedEmail, code });
      onAuthenticated(nextSession);
    } catch (error) {
      showError(toErrorMessage(error));
    } finally {
      setBusy(false);
    }
  };

  return (
    <MobileScroll className="app-screen auth-page">
      <main className="login-layout">
        <section className="login-hero" aria-label="ChatIM 品牌介绍">
          <img
            className="login-hero-image"
            src="/app-assets/login-connection-collage.png"
            alt="朋友相聚、用手机保持联系和城市旅行的温暖瞬间"
            draggable={false}
          />
          <div className="login-hero-shade" aria-hidden="true" />
          <div className="hero-brand">
            <span className="brand-mark" aria-hidden="true">
              C
            </span>
            <strong>ChatIM</strong>
          </div>
          <div className="hero-copy">
            <span>无论相隔多远</span>
            <h1>让每一次连接，<br />都温柔发生</h1>
          </div>
        </section>

        <section className="auth-sheet" aria-labelledby="login-title">
          <div className="auth-heading-row">
            <div>
              <p className="eyebrow">WELCOME BACK</p>
              <h2 id="login-title">登录 ChatIM</h2>
              <p>继续分享此刻，也不错过重要的人。</p>
            </div>
            {demoModeEnabled ? <span className="demo-chip">演示模式</span> : null}
          </div>

          <div className="login-mode-switch" role="tablist" aria-label="登录方式">
            <button
              type="button"
              role="tab"
              aria-selected={mode === "password"}
              className={mode === "password" ? "active" : ""}
              onClick={() => switchMode("password")}
            >
              密码登录
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mode === "code"}
              className={mode === "code" ? "active" : ""}
              onClick={() => switchMode("code")}
            >
              验证码登录
            </button>
          </div>

          <form className="auth-form" onSubmit={handleSubmit} noValidate>
            <AuthField icon={<EnvelopeClosedIcon />}>
              <KeyboardInput
                aria-label="邮箱地址"
                type="email"
                inputMode="email"
                autoComplete="email"
                placeholder="邮箱地址"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </AuthField>

            {mode === "password" ? (
              <>
                <AuthField icon={<LockClosedIcon />}>
                  <KeyboardInput
                    aria-label="密码"
                    type={showPassword ? "text" : "password"}
                    autoComplete="current-password"
                    placeholder="6–20 位密码"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                  />
                  <button
                    type="button"
                    className="field-action icon-button"
                    onClick={() => setShowPassword((value) => !value)}
                    aria-label={showPassword ? "隐藏密码" : "显示密码"}
                  >
                    {showPassword ? <EyeClosedIcon /> : <EyeOpenIcon />}
                  </button>
                </AuthField>
                <button
                  type="button"
                  className="forgot-button"
                  onClick={() => {
                    keyboard.hide();
                    setMessageTone("success");
                    setMessage("密码找回将在下一轮开放，可先使用验证码登录");
                  }}
                >
                  忘记密码？
                </button>
              </>
            ) : (
              <AuthField icon={<LockClosedIcon />}>
                <KeyboardInput
                  aria-label="验证码"
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  placeholder="6 位验证码"
                  value={code}
                  onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                />
                <button
                  type="button"
                  className="field-action code-button"
                  disabled={sendingCode || countdown > 0}
                  onClick={handleCaptcha}
                >
                  {sendingCode ? "发送中" : countdown > 0 ? `${countdown}s` : "获取验证码"}
                </button>
              </AuthField>
            )}

            <InlineMessage message={message} tone={messageTone} />

            <button type="submit" className="primary-button" disabled={busy}>
              {busy ? <><span className="button-spinner" aria-hidden="true" />正在登录</> : "登录"}
            </button>
          </form>

          <div className="register-prompt">
            <span>第一次来到 ChatIM？</span>
            <button type="button" onClick={() => flow.push(registerFlowScreen(onAuthenticated))}>
              创建账号
            </button>
          </div>

          <p className="legal-copy">
            登录即代表你同意《用户协议》和《隐私政策》
          </p>
        </section>
      </main>
    </MobileScroll>
  );
}

function RegisterScreen({ onAuthenticated }: { onAuthenticated: (session: AuthSession) => void }) {
  const keyboard = useKeyboard();
  const [nickname, setNickname] = useState("");
  const [email, setEmail] = useState(demoModeEnabled ? "new@chatim.cn" : "");
  const [code, setCode] = useState(demoModeEnabled ? "123456" : "");
  const [password, setPassword] = useState(demoModeEnabled ? "123456" : "");
  const [confirmPassword, setConfirmPassword] = useState(demoModeEnabled ? "123456" : "");
  const [agreed, setAgreed] = useState(true);
  const [busy, setBusy] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [message, setMessage] = useState("");
  const [messageTone, setMessageTone] = useState<"error" | "success">("error");

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown((value) => value - 1), 1_000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  const showError = (value: string) => {
    setMessageTone("error");
    setMessage(value);
  };

  const handleCaptcha = async () => {
    keyboard.hide();
    if (!emailPattern.test(email.trim())) {
      showError("请先填写正确的邮箱地址");
      return;
    }

    setSendingCode(true);
    setMessage("");
    try {
      await sendCaptcha(email.trim());
      setCountdown(60);
      setMessageTone("success");
      setMessage(demoModeEnabled ? "验证码已发送，演示验证码是 123456" : "验证码已发送，请留意邮箱");
    } catch (error) {
      showError(toErrorMessage(error));
    } finally {
      setSendingCode(false);
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    keyboard.hide();

    if (nickname.trim().length < 2 || nickname.trim().length > 20) {
      showError("昵称需要为 2–20 个字符");
      return;
    }
    if (!emailPattern.test(email.trim())) {
      showError("请输入正确的邮箱地址");
      return;
    }
    if (!/^\d{6}$/.test(code)) {
      showError("请输入 6 位数字验证码");
      return;
    }
    if (password.length < 6 || password.length > 20) {
      showError("密码长度需要为 6–20 位");
      return;
    }
    if (password !== confirmPassword) {
      showError("两次输入的密码不一致");
      return;
    }
    if (!agreed) {
      showError("请先阅读并同意用户协议和隐私政策");
      return;
    }

    setBusy(true);
    setMessage("");
    try {
      const nextSession = await registerAccount({
        nickname: nickname.trim(),
        email: email.trim(),
        code,
        password,
        confirmPassword,
      });
      onAuthenticated(nextSession);
    } catch (error) {
      showError(toErrorMessage(error));
    } finally {
      setBusy(false);
    }
  };

  return (
    <MobileScroll className="app-screen register-page">
      <main className="register-content">
        <div className="register-intro">
          <p className="eyebrow">WELCOME TO CHATIM</p>
          <h1>从一句你好开始</h1>
          <p>创建你的个人名片，和在意的人保持联系。</p>
        </div>

        <form className="auth-form register-form" onSubmit={handleSubmit} noValidate>
          <AuthField icon={<PersonIcon />}>
            <KeyboardInput
              aria-label="昵称"
              type="text"
              autoComplete="nickname"
              maxLength={20}
              placeholder="你的昵称"
              value={nickname}
              onChange={(event) => setNickname(event.target.value)}
            />
          </AuthField>
          <AuthField icon={<EnvelopeClosedIcon />}>
            <KeyboardInput
              aria-label="邮箱地址"
              type="email"
              inputMode="email"
              autoComplete="email"
              placeholder="邮箱地址"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </AuthField>
          <AuthField icon={<LockClosedIcon />}>
            <KeyboardInput
              aria-label="验证码"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              placeholder="6 位验证码"
              value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
            />
            <button
              type="button"
              className="field-action code-button"
              disabled={sendingCode || countdown > 0}
              onClick={handleCaptcha}
            >
              {sendingCode ? "发送中" : countdown > 0 ? `${countdown}s` : "获取验证码"}
            </button>
          </AuthField>
          <AuthField icon={<LockClosedIcon />}>
            <KeyboardInput
              aria-label="设置密码"
              type="password"
              autoComplete="new-password"
              placeholder="设置 6–20 位密码"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </AuthField>
          <PasswordStrength password={password} />
          <AuthField icon={<CheckIcon />}>
            <KeyboardInput
              aria-label="确认密码"
              type="password"
              autoComplete="new-password"
              placeholder="再次输入密码"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
            />
          </AuthField>

          <label className="agreement-row">
            <input
              type="checkbox"
              checked={agreed}
              onChange={(event) => setAgreed(event.target.checked)}
            />
            <span className="agreement-check" aria-hidden="true">{agreed ? <CheckIcon /> : null}</span>
            <span>我已阅读并同意《用户协议》和《隐私政策》</span>
          </label>

          <InlineMessage message={message} tone={messageTone} />

          <button type="submit" className="primary-button" disabled={busy}>
            {busy ? <><span className="button-spinner" aria-hidden="true" />正在创建</> : "创建账号"}
          </button>
        </form>
      </main>
    </MobileScroll>
  );
}

function AuthField({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <div className="auth-field" data-scroll-drag="ignore">
      <span className="field-icon" aria-hidden="true">{icon}</span>
      {children}
    </div>
  );
}

function InlineMessage({ message, tone }: { message: string; tone: "error" | "success" }) {
  if (!message) return <div className="inline-message-placeholder" aria-hidden="true" />;
  return <p className={`inline-message ${tone}`} role={tone === "error" ? "alert" : "status"}>{message}</p>;
}

function PasswordStrength({ password }: { password: string }) {
  const score = password.length === 0
    ? 0
    : Math.min(
        3,
        Number(password.length >= 6) +
          Number(/[A-Za-z]/.test(password) && /\d/.test(password)) +
          Number(password.length >= 10 || /[^A-Za-z0-9]/.test(password)),
      );
  const label = score <= 1 ? "建议组合字母和数字" : score === 2 ? "密码强度适中" : "密码强度较好";

  return (
    <div className={`password-strength score-${score}`} aria-live="polite">
      <div className="strength-bars" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <span>{label}</span>
    </div>
  );
}

function MainShell({ session, onLogout }: { session: AuthSession; onLogout: () => Promise<void> }) {
  const keyboard = useKeyboard();
  const { bottomInset, isKeyboardVisible } = useKeyboardInsets();
  const [tab, setTab] = useState<TabId>("messages");
  const [query, setQuery] = useState("");
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>(() => loadConversations(session.userId));
  const [messages, setMessages] = useState<Record<string, ChatMessage[]>>(() => loadMessages(session.userId));
  const [drafts, setDrafts] = useState<Record<string, string>>(() => loadDrafts(session.userId));
  const [contacts, setContacts] = useState<Contact[]>(() => loadContacts(session.userId));
  const [applications, setApplications] = useState<FriendApplication[]>(() => loadApplications(session.userId));
  const [groups, setGroups] = useState<GroupRecord[]>(() => loadGroups(session.userId));
  const [contactSurface, setContactSurface] = useState<ContactSurface>(null);
  const [notice, setNotice] = useState("");
  const [connectionState, setConnectionState] = useState<RealtimeConnectionState>("connecting");
  const [syncState, setSyncState] = useState<"idle" | "syncing" | "synced" | "failed">("idle");
  const [lastSyncTime, setLastSyncTime] = useState("");
  const realtimeRef = useRef<ChatRealtimeClient | null>(null);
  const conversationsRef = useRef(conversations);
  const messagesRef = useRef(messages);
  const syncOfflineRef = useRef<(() => Promise<void>) | null>(null);
  const syncSessionSummariesRef = useRef<(() => Promise<void>) | null>(null);
  const submittedReadPositionsRef = useRef<Record<string, string>>({});
  const activeConversationIdRef = useRef<string | null>(null);
  const offlineCursorRef = useRef<string | null>(loadOfflineCursor(session.userId));
  const seenServerKeysRef = useRef<Set<string> | null>(null);
  const applyServerMessagesRef = useRef<(
    incoming: RealtimeMessage[],
    history?: boolean,
    confirmed?: boolean,
  ) => number>(() => 0);

  if (!seenServerKeysRef.current) seenServerKeysRef.current = collectServerMessageKeys(messages);
  conversationsRef.current = conversations;
  messagesRef.current = messages;
  activeConversationIdRef.current = activeConversationId;

  const tabs = useMemo(
    () => [
      { id: "messages" as const, label: "消息", icon: <ChatBubbleIcon /> },
      { id: "contacts" as const, label: "联系人", icon: <PersonIcon /> },
      { id: "discover" as const, label: "发现", icon: <MagnifyingGlassIcon /> },
      { id: "profile" as const, label: "我的", icon: <GearIcon /> },
    ],
    [],
  );

  const unreadTotal = conversations.reduce((total, item) => total + item.unread, 0);
  const activeConversation = conversations.find((item) => item.id === activeConversationId) ?? null;
  const activeLastMessageId = activeConversation?.lastMessageId;
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const filteredConversations = conversations.filter((item) =>
    `${item.name} ${item.preview}`.toLocaleLowerCase().includes(normalizedQuery),
  );
  const filteredContacts = contacts.filter((item) =>
    `${item.name} ${item.note}`.toLocaleLowerCase().includes(normalizedQuery),
  );
  const applicationUnread = applications.filter((item) => item.isReceiver === 1 && item.status === 0).length;
  const syncPresentation = describeSyncState(connectionState, syncState, lastSyncTime);

  applyServerMessagesRef.current = (incoming, history = false, confirmed = false) => {
    const seen = seenServerKeysRef.current!;
    const accepted = incoming
      .filter(isRealtimeMessage)
      .sort((a, b) => (a.createdTime || 0) - (b.createdTime || 0))
      .filter((message) => {
        const messageKey = message.messageId == null ? "" : `message:${message.messageId}`;
        const clientKey = message.clientMessageId ? `client:${message.clientMessageId}` : "";
        if ((messageKey && seen.has(messageKey)) || (!messageKey && clientKey && seen.has(clientKey))) return false;
        if (messageKey) seen.add(messageKey);
        if (clientKey) seen.add(clientKey);
        return true;
      });

    if (accepted.length === 0) return 0;

    const nextMessages = { ...messagesRef.current };
    accepted.forEach((message) => {
      const sessionId = String(message.sessionId);
      const existing = [...(nextMessages[sessionId] ?? [])];
      const converted = toChatMessage(message, session.userId);
      if (!confirmed && converted.mine) converted.status = "sending";
      const matchIndex = message.clientMessageId
        ? existing.findIndex((item) => item.clientMessageId === message.clientMessageId || item.id === message.clientMessageId)
        : -1;
      if (matchIndex >= 0) {
        const local = existing[matchIndex];
        existing[matchIndex] = {
          ...local,
          ...converted,
          imageName: local.imageName || converted.imageName,
          imageWidth: local.imageWidth,
          imageHeight: local.imageHeight,
          imageSize: local.imageSize,
          uploadProgress: undefined,
          status: confirmed ? "sent" : local.status,
        };
      } else {
        existing.push(converted);
      }
      nextMessages[sessionId] = existing.sort(compareChatMessages);
    });
    messagesRef.current = nextMessages;
    setMessages(nextMessages);

    let nextConversations = conversationsRef.current;
    if (!history) {
      nextConversations = [...conversationsRef.current];
      const grouped = new Map<string, RealtimeMessage[]>();
      accepted.forEach((message) => {
        const key = String(message.sessionId);
        grouped.set(key, [...(grouped.get(key) ?? []), message]);
      });
      grouped.forEach((batch, sessionId) => {
        const latest = batch[batch.length - 1];
        const incomingCount = batch.filter((message) => String(message.senderId) !== String(session.userId)).length;
        const currentIndex = nextConversations.findIndex((item) => item.id === sessionId);
        const preview = realtimePreview(latest);
        const active = activeConversationIdRef.current === sessionId;
        if (currentIndex >= 0) {
          const existing = nextConversations[currentIndex];
          const updated: Conversation = {
            ...existing,
            preview,
            time: formatConversationTime(latest.createdTime),
            unread: active && demoModeEnabled ? 0 : existing.unread + incomingCount,
            lastMessageId: latest.messageId == null ? existing.lastMessageId : String(latest.messageId),
            failed: false,
          };
          nextConversations.splice(currentIndex, 1);
          nextConversations.unshift(updated);
        } else {
          const contact = contacts.find((item) => item.conversationId === sessionId);
          const group = groups.find((item) => item.sessionId === sessionId);
          const name = group?.name
            || contact?.name
            || (latest.sessionType === 1 ? "新群聊" : latest.nickname || "新消息");
          nextConversations.unshift({
            id: sessionId,
            name,
            avatar: group?.avatar || contact?.avatar || name.slice(0, 1),
            avatarTone: contact?.avatarTone || toneFromId(sessionId),
            preview,
            time: formatConversationTime(latest.createdTime),
            unread: active && demoModeEnabled ? 0 : incomingCount,
            lastMessageId: latest.messageId == null ? undefined : String(latest.messageId),
            presence: contact?.presence,
            peerId: contact?.id || (latest.sessionType === 0 && String(latest.senderId) !== String(session.userId)
              ? String(latest.senderId)
              : undefined),
            group: latest.sessionType === 1,
            membersCount: group?.members.length,
          });
        }
      });
      conversationsRef.current = nextConversations;
      setConversations(nextConversations);
    }
    saveChatState(session.userId, nextConversations, nextMessages);

    return accepted.length;
  };

  const applyMessageAck = (ack: RealtimeMessageAck) => {
    if (ack.stage === "accepted") return;
    setMessages((current) => {
      const next = { ...current };
      const targetSessionId = ack.sessionId == null ? null : String(ack.sessionId);
      Object.entries(next).forEach(([sessionId, items]) => {
        if (targetSessionId && sessionId !== targetSessionId) return;
        next[sessionId] = items.map((item) => {
          if (item.clientMessageId !== ack.clientMessageId) return item;
          return {
            ...item,
            messageId: ack.messageId == null ? item.messageId : String(ack.messageId),
            createdTime: ack.createdTime ?? item.createdTime,
            time: ack.createdTime ? formatClock(new Date(ack.createdTime)) : item.time,
            status: ack.stage === "persisted" ? "sent" : "failed",
          };
        });
      });
      return next;
    });
    if (ack.sessionId != null) {
      setConversations((current) => current.map((conversation) =>
        conversation.id === String(ack.sessionId)
          ? { ...conversation, failed: ack.stage === "failed" }
          : conversation,
      ));
    }
  };

  const reconcilePendingMessageStatuses = async () => {
    const pending = Object.values(messagesRef.current)
      .flat()
      .filter((message) => message.mine
        && Boolean(message.clientMessageId)
        && (message.status === "sending" || message.status === "unknown"))
      .slice(0, 50);

    const results = await Promise.allSettled(
      pending.map((message) => fetchMessageStatus(session, message.clientMessageId!)),
    );
    results.forEach((result, index) => {
      if (result.status !== "fulfilled") return;
      const status = result.value;
      if (status.status === "persisted" || status.status === "failed") {
        const { status: stage, ...ack } = status;
        applyMessageAck({
          ...ack,
          stage,
        });
        return;
      }
      const clientMessageId = pending[index].clientMessageId;
      setMessages((current) => Object.fromEntries(
        Object.entries(current).map(([sessionId, items]) => [
          sessionId,
          items.map((item) => item.clientMessageId === clientMessageId
            ? { ...item, status: "unknown" as const }
            : item),
        ]),
      ));
    });
  };

  useEffect(() => {
    saveDrafts(session.userId, drafts);
  }, [drafts, session.userId]);

  useEffect(() => {
    saveChatState(session.userId, conversations, messages);
  }, [conversations, messages, session.userId]);

  useEffect(() => {
    saveContactState(session.userId, contacts, applications);
  }, [applications, contacts, session.userId]);

  useEffect(() => {
    setConversations((items) => {
      let changed = false;
      const next = items.map((conversation) => {
        if (conversation.group || conversation.peerId) return conversation;
        const peerId = contacts.find((contact) => contact.conversationId === conversation.id)?.id;
        if (!peerId) return conversation;
        changed = true;
        return { ...conversation, peerId };
      });
      return changed ? next : items;
    });
  }, [contacts]);

  useEffect(() => {
    saveGroups(session.userId, groups);
  }, [groups, session.userId]);

  syncSessionSummariesRef.current = async () => {
    if (demoModeEnabled) return;
    const summaries: SessionSummary[] = [];
    let cursor: string | null = null;
    let pageCount = 0;
    do {
      const page = await fetchSessionList(session, cursor);
      summaries.push(...page.items);
      pageCount += 1;
      if (!page.hasMore) break;
      const nextCursor = page.nextCursor || null;
      if (!nextCursor || nextCursor === cursor || pageCount >= 20) break;
      cursor = nextCursor;
    } while (true);

    const current = conversationsRef.current;
    const next = summaries.map((summary) => {
      const id = String(summary.sessionId);
      const existing = current.find((conversation) => conversation.id === id);
      const name = summary.name?.trim() || existing?.name || (summary.sessionType === 1 ? "群聊" : "新消息");
      const lastMessage = summary.lastMessage || null;
      return {
        ...existing,
        id,
        name,
        avatar: existing?.avatar || name.slice(0, 1) || (summary.sessionType === 1 ? "群" : "友"),
        avatarTone: existing?.avatarTone || toneFromId(id),
        preview: lastMessage ? realtimePreview(lastMessage) : existing?.preview || "还没有消息",
        time: formatConversationTime(summary.lastMessageTime || summary.updatedTime || undefined),
        unread: Math.max(0, summary.unreadCount || 0),
        muted: summary.muted,
        pinned: summary.pinned,
        group: summary.sessionType === 1,
        membersCount: summary.memberCount ?? existing?.membersCount,
        peerId: summary.peerId == null ? existing?.peerId : String(summary.peerId),
        lastMessageId: summary.lastMessageId == null ? existing?.lastMessageId : String(summary.lastMessageId),
        lastReadMessageId: summary.lastReadMessageId == null
          ? existing?.lastReadMessageId
          : String(summary.lastReadMessageId),
      } satisfies Conversation;
    });
    conversationsRef.current = next;
    setConversations(next);
    saveChatState(session.userId, next, messagesRef.current);
  };

  useEffect(() => {
    let active = true;
    let syncRun = 0;

    const syncOffline = async () => {
      const run = ++syncRun;
      setSyncState("syncing");
      try {
        let cursor = offlineCursorRef.current;
        let serverTime = Date.now();
        let hasMore = false;
        let invalidCursorReset = false;
        do {
          let page: OfflineSyncResponse;
          try {
            page = await syncOfflineMessages(session, cursor);
          } catch (error) {
            if (error instanceof RealtimeApiError
              && error.code === 90008
              && cursor
              && !invalidCursorReset) {
              cursor = null;
              offlineCursorRef.current = null;
              clearOfflineCursor(session.userId);
              invalidCursorReset = true;
              hasMore = true;
              continue;
            }
            throw error;
          }
          if (!active || run !== syncRun) return;
          applyServerMessagesRef.current(page.items, false, true);
          serverTime = page.serverTime;
          hasMore = page.hasMore;
          const nextCursor = page.nextCursor || null;
          if (nextCursor && nextCursor !== cursor) {
            cursor = nextCursor;
            offlineCursorRef.current = nextCursor;
            saveOfflineCursor(session.userId, nextCursor);
          } else if (hasMore) {
            throw new RealtimeApiError("消息同步游标没有前进");
          }
        } while (hasMore);
        setLastSyncTime(formatClock(new Date(serverTime)));
        setSyncState("synced");
      } catch {
        if (active && run === syncRun) setSyncState("failed");
      }
    };

    syncOfflineRef.current = syncOffline;
    const client = new ChatRealtimeClient({
      session,
      onState: (state) => {
        if (active) setConnectionState(state);
      },
      onMessage: (message) => {
        if (active) applyServerMessagesRef.current([message]);
      },
      onAck: (ack) => {
        if (!active) return;
        applyMessageAck(ack);
        if (ack.stage === "failed" && ack.errorMessage) setNotice(ack.errorMessage);
      },
      onConnected: () => {
        if (!active) return;
        void syncSessionSummariesRef.current?.().catch(() => {
          // Keep the local cache visible while the session summary service is unavailable.
        });
        void syncOffline();
        void reconcilePendingMessageStatuses();
        if (!demoModeEnabled) {
          Promise.all([fetchFriends(session), fetchFriendApplications(session), fetchFriendApplicationCount(session)])
            .then(([friendItems, applicationItems]) => {
              if (!active) return;
              setContacts(friendItems.map(toContact));
              setApplications(applicationItems);
            })
            .catch(() => {
              // Message recovery remains usable while the contact service is unavailable.
            });
        }
      },
    });
    realtimeRef.current = client;
    client.connect();

    const reconnectWhenVisible = () => {
      if (document.visibilityState === "visible" && !client.isConnected()) client.reconnectNow();
    };
    const reconnectWhenOnline = () => {
      if (!client.isConnected()) client.reconnectNow();
    };
    document.addEventListener("visibilitychange", reconnectWhenVisible);
    window.addEventListener("online", reconnectWhenOnline);

    return () => {
      active = false;
      syncRun += 1;
      document.removeEventListener("visibilitychange", reconnectWhenVisible);
      window.removeEventListener("online", reconnectWhenOnline);
      client.disconnect();
      if (realtimeRef.current === client) realtimeRef.current = null;
      if (syncOfflineRef.current === syncOffline) syncOfflineRef.current = null;
    };
  }, [session.accessToken, session.nettyUri, session.refreshToken, session.userId]);

  useEffect(() => {
    if (demoModeEnabled || !activeConversationId) return;
    const lastMessageId = activeLastMessageId;
    if (!lastMessageId || submittedReadPositionsRef.current[activeConversationId] === lastMessageId) return;

    let active = true;
    submittedReadPositionsRef.current[activeConversationId] = lastMessageId;
    void markSessionRead(session, activeConversationId, lastMessageId)
      .then((result) => {
        if (!active) return;
        const confirmedReadId = String(result.lastReadMessageId);
        setConversations((items) => items.map((item) => item.id === activeConversationId
          ? {
              ...item,
              unread: Math.max(0, result.unreadCount || 0),
              lastReadMessageId: confirmedReadId,
            }
          : item));
      })
      .catch(() => {
        if (submittedReadPositionsRef.current[activeConversationId] === lastMessageId) {
          delete submittedReadPositionsRef.current[activeConversationId];
        }
      });

    return () => {
      active = false;
    };
  }, [
    activeConversationId,
    activeLastMessageId,
    session.accessToken,
    session.refreshToken,
    session.userId,
  ]);

  useEffect(() => {
    if (demoModeEnabled) return;
    let active = true;
    Promise.all([fetchFriends(session), fetchFriendApplications(session), fetchFriendApplicationCount(session)])
      .then(([friendItems, applicationItems]) => {
        if (!active) return;
        setContacts(friendItems.map(toContact));
        setApplications(applicationItems);
      })
      .catch(() => {
        // Keep the last local cache visible when the gateway or UserService is unavailable.
      });
    return () => {
      active = false;
    };
  }, [session]);

  const switchTab = (nextTab: TabId) => {
    keyboard.hide();
    setTab(nextTab);
    setQuery("");
    setContactSurface(null);
    setNotice("");
  };

  const sendRealtimeMessage = (
    conversation: Conversation,
    type: 0 | 1,
    content: string,
    clientMessageId: string,
  ) => {
    const peerId = conversation.peerId
      || contacts.find((contact) => contact.conversationId === conversation.id)?.id;
    if (!conversation.group && !peerId && !demoModeEnabled) return false;

    const payload: OutgoingRealtimeMessage = {
      sessionId: conversation.id,
      receiverId: conversation.group ? null : peerId,
      senderId: session.userId,
      type,
      sessionType: conversation.group ? 1 : 0,
      clientMessageId,
      body: {
        content,
        replyId: null,
        redPacketId: null,
        redPacketWrapperText: null,
      },
    };
    const accepted = realtimeRef.current?.send(payload) ?? false;
    window.setTimeout(() => {
      void (async () => {
        let resolvedStatus: MessageStatus = "unknown";
        if (accepted) {
          try {
            const result = await fetchMessageStatus(session, clientMessageId);
            if (result.status === "persisted") resolvedStatus = "sent";
            if (result.status === "failed") resolvedStatus = "failed";
          } catch {
            // Keep an unknown result when the status service is temporarily unavailable.
          }
        }
        setMessages((items) => ({
          ...items,
          [conversation.id]: (items[conversation.id] ?? []).map((item) =>
            item.clientMessageId === clientMessageId && item.status === "sending"
              ? { ...item, status: resolvedStatus }
              : item,
          ),
        }));
      })();
    }, accepted ? 8_000 : 260);
    return accepted;
  };

  const openConversation = (conversationId: string) => {
    keyboard.hide();
    if (demoModeEnabled) {
      setConversations((items) =>
        items.map((item) => item.id === conversationId ? { ...item, unread: 0 } : item),
      );
    }
    setActiveConversationId(conversationId);
  };

  const openContactConversation = (contact: Contact) => {
    setContactSurface(null);
    const existing = conversations.find((item) => item.id === contact.conversationId);
    if (existing) {
      if (!existing.peerId) {
        setConversations((items) => items.map((item) => item.id === existing.id ? { ...item, peerId: contact.id } : item));
      }
      openConversation(existing.id);
      return;
    }

    const nextConversation: Conversation = {
      id: contact.conversationId,
      name: contact.name,
      avatar: contact.avatar,
      avatarTone: contact.avatarTone,
      preview: "你们已经是好友了，打个招呼吧",
      time: "刚刚",
      unread: 0,
      presence: contact.presence,
      peerId: contact.id,
    };
    setConversations((items) => [nextConversation, ...items]);
    setMessages((items) => ({ ...items, [contact.conversationId]: [] }));
    setActiveConversationId(contact.conversationId);
  };

  const ensureContactFromProfile = (profile: FriendProfile, decision?: ApplicationDecision) => {
    const next = toContact({
      userId: profile.userId,
      nickname: profile.nickname,
      avatar: profile.avatar,
      status: 0,
      signature: profile.signature,
      sessionId: decision?.sessionId || profile.sessionId || `c-${profile.userId}`,
    });
    setContacts((items) => [next, ...items.filter((item) => item.id !== next.id)]);
    return next;
  };

  const openGroupConversation = (group: GroupRecord) => {
    setContactSurface(null);
    const existing = conversations.find((item) => item.id === group.sessionId);
    if (existing) {
      openConversation(existing.id);
      return;
    }
    const nextConversation: Conversation = {
      id: group.sessionId,
      name: group.name,
      avatar: group.avatar,
      avatarTone: "green",
      preview: "群聊创建成功，和大家打个招呼吧",
      time: "刚刚",
      unread: 0,
      group: true,
      membersCount: group.members.length,
    };
    setConversations((items) => [nextConversation, ...items]);
    setMessages((items) => ({ ...items, [group.sessionId]: [] }));
    setActiveConversationId(group.sessionId);
  };

  const applyCreatedGroup = (result: CreateGroupResult, selectedContacts: Contact[]) => {
    const failedIds = new Set(result.failedMemberIds.map(String));
    const successfulContacts = selectedContacts.filter((contact) => !failedIds.has(contact.id));
    const group: GroupRecord = {
      sessionId: String(result.sessionId),
      name: result.sessionName,
      avatar: result.sessionName.slice(0, 1) || "群",
      avatarUrl: result.avatar || null,
      creatorId: String(result.creatorId),
      members: [
        { id: String(session.userId), name: session.nickname, avatar: session.nickname.slice(0, 1) || "我", role: "owner" },
        ...successfulContacts.map((contact) => ({ id: contact.id, name: contact.name, avatar: contact.avatar, role: "member" as const })),
      ],
    };
    setGroups((items) => [group, ...items.filter((item) => item.sessionId !== group.sessionId)]);
    setConversations((items) => [{
      id: group.sessionId,
      name: group.name,
      avatar: group.avatar,
      avatarTone: "green",
      preview: "群聊创建成功，和大家打个招呼吧",
      time: "刚刚",
      unread: 0,
      group: true,
      membersCount: group.members.length,
    }, ...items.filter((item) => item.id !== group.sessionId)]);
    setMessages((items) => ({ ...items, [group.sessionId]: [] }));
    setContactSurface({ kind: "group-result", group, failedIds: [...failedIds] });
  };

  if (contactSurface?.kind === "search") {
    return (
      <FriendSearchScreen
        session={session}
        onBack={() => setContactSurface(null)}
        onOpenProfile={(profile) => setContactSurface({ kind: "profile", profile, returnTo: "search" })}
      />
    );
  }

  if (contactSurface?.kind === "applications") {
    return (
      <FriendApplicationsScreen
        session={session}
        applications={applications}
        onApplicationsChange={setApplications}
        onBack={() => setContactSurface(null)}
        onAccepted={(application, decision) => ensureContactFromProfile({
          userId: application.userId,
          nickname: application.nickname,
          avatar: application.avatar,
          signature: application.msg,
          status: 0,
          sessionId: typeof decision === "object" ? decision.sessionId : `c-${application.userId}`,
        }, typeof decision === "object" ? decision : undefined)}
        onMessage={(application) => {
          const contact = contacts.find((item) => item.id === application.userId) || ensureContactFromProfile({
            userId: application.userId,
            nickname: application.nickname,
            avatar: application.avatar,
            signature: application.msg,
            status: 0,
            sessionId: `c-${application.userId}`,
          });
          openContactConversation(contact);
        }}
      />
    );
  }

  if (contactSurface?.kind === "profile") {
    const initialProfile = contactSurface.profile || contactToProfile(contactSurface.contact!);
    return (
      <FriendProfileScreen
        session={session}
        initialProfile={initialProfile}
        shouldLoadDetail={!demoModeEnabled && Boolean(contactSurface.contact)}
        onBack={() => setContactSurface(contactSurface.returnTo === "search" ? { kind: "search" } : null)}
        onMessage={(profile) => openContactConversation(ensureContactFromProfile(profile))}
      />
    );
  }

  if (contactSurface?.kind === "groups") {
    return (
      <GroupListScreen
        groups={groups}
        onBack={() => setContactSurface(null)}
        onCreate={() => setContactSurface({ kind: "group-create" })}
        onOpen={openGroupConversation}
      />
    );
  }

  if (contactSurface?.kind === "group-create") {
    return (
      <GroupMemberPicker
        mode="create"
        session={session}
        contacts={contacts}
        onBack={() => setContactSurface({ kind: "groups" })}
        onCreated={applyCreatedGroup}
      />
    );
  }

  if (contactSurface?.kind === "group-result") {
    return (
      <GroupCreateResultScreen
        group={contactSurface.group}
        failedIds={contactSurface.failedIds}
        contacts={contacts}
        onBack={() => setContactSurface({ kind: "groups" })}
        onEnter={() => openGroupConversation(contactSurface.group)}
      />
    );
  }

  if (contactSurface?.kind === "group-settings") {
    const group = groups.find((item) => item.sessionId === contactSurface.groupId);
    if (group) {
      return (
        <GroupSettingsScreen
          group={group}
          onBack={() => setContactSurface(null)}
          onInvite={() => setContactSurface({ kind: "group-invite", groupId: group.sessionId })}
        />
      );
    }
  }

  if (contactSurface?.kind === "group-invite") {
    const group = groups.find((item) => item.sessionId === contactSurface.groupId);
    if (group) {
      return (
        <GroupMemberPicker
          mode="invite"
          session={session}
          contacts={contacts.filter((contact) => !group.members.some((member) => member.id === contact.id))}
          group={group}
          onBack={() => setContactSurface({ kind: "group-settings", groupId: group.sessionId })}
          onInvited={(result, selectedContacts) => {
            const successful = new Set(result.successIds.map(String));
            setGroups((items) => items.map((item) => item.sessionId === group.sessionId ? {
              ...item,
              members: [
                ...item.members,
                ...selectedContacts
                  .filter((contact) => successful.has(contact.id))
                  .map((contact) => ({ id: contact.id, name: contact.name, avatar: contact.avatar, role: "member" as const })),
              ],
            } : item));
            setConversations((items) => items.map((item) => item.id === group.sessionId
              ? { ...item, membersCount: item.membersCount ? item.membersCount + successful.size : group.members.length + successful.size }
              : item));
          }}
        />
      );
    }
  }

  if (activeConversation) {
    return (
      <ChatScreen
        conversation={activeConversation}
        onOpenDetails={() => {
          if (activeConversation.group) {
            const existingGroup = groups.find((item) => item.sessionId === activeConversation.id);
            if (existingGroup) setContactSurface({ kind: "group-settings", groupId: activeConversation.id });
          }
        }}
        messages={messages[activeConversation.id] ?? []}
        draft={drafts[activeConversation.id] ?? activeConversation.draft ?? ""}
        connectionLabel={syncPresentation.chatLabel}
        onDraftChange={(value) => setDrafts((items) => ({ ...items, [activeConversation.id]: value }))}
        onBack={() => {
          keyboard.hide();
          setActiveConversationId(null);
        }}
        onSend={(content) => {
          const clientMessageId = makeClientMessageId();
          const sentAt = new Date();
          const outgoing: ChatMessage = {
            id: clientMessageId,
            clientMessageId,
            mine: true,
            content,
            time: formatClock(sentAt),
            createdTime: sentAt.getTime(),
            status: "sending",
          };

          setMessages((items) => ({
            ...items,
            [activeConversation.id]: [...(items[activeConversation.id] ?? []), outgoing],
          }));
          setDrafts((items) => ({ ...items, [activeConversation.id]: "" }));
          setConversations((items) => [
            ...items
              .map((item) => item.id === activeConversation.id
                ? { ...item, preview: content, time: "刚刚", failed: false, draft: undefined }
                : item)
              .sort((a, b) => Number(b.id === activeConversation.id) - Number(a.id === activeConversation.id)),
          ]);

          sendRealtimeMessage(activeConversation, 0, content, clientMessageId);
        }}
        onSendImage={async (file) => {
          const clientMessageId = makeClientMessageId();
          let queued = false;
          try {
            const prepared = await prepareChatImage(file);
            queued = true;
            const outgoing: ChatMessage = {
              id: clientMessageId,
              clientMessageId,
              mine: true,
              kind: "image",
              content: prepared.previewUrl,
              imageName: prepared.originalName,
              imageWidth: prepared.width,
              imageHeight: prepared.height,
              imageSize: prepared.size,
              uploadProgress: 0,
              time: formatClock(new Date()),
              createdTime: Date.now(),
              status: "uploading",
            };
            setMessages((items) => ({
              ...items,
              [activeConversation.id]: [...(items[activeConversation.id] ?? []), outgoing],
            }));
            setConversations((items) => [
              ...items
                .map((item) => item.id === activeConversation.id
                  ? { ...item, preview: "[图片]", time: "刚刚", failed: false, draft: undefined }
                  : item)
                .sort((a, b) => Number(b.id === activeConversation.id) - Number(a.id === activeConversation.id)),
            ]);

            const uploaded = await uploadChatImage(session, prepared, (progress) => {
              setMessages((items) => ({
                ...items,
                [activeConversation.id]: (items[activeConversation.id] ?? []).map((item) =>
                  item.id === clientMessageId ? { ...item, uploadProgress: progress } : item,
                ),
              }));
            });
            setMessages((items) => ({
              ...items,
              [activeConversation.id]: (items[activeConversation.id] ?? []).map((item) =>
                item.id === clientMessageId
                  ? { ...item, content: uploaded.downloadUrl, uploadProgress: 100, status: "sending" }
                  : item,
              ),
            }));
            sendRealtimeMessage(activeConversation, 1, uploaded.downloadUrl, clientMessageId);
          } catch (error) {
            if (queued) {
              setMessages((items) => ({
                ...items,
                [activeConversation.id]: (items[activeConversation.id] ?? []).map((item) =>
                  item.id === clientMessageId ? { ...item, status: "failed" } : item,
                ),
              }));
              setConversations((items) => items.map((item) => item.id === activeConversation.id
                ? { ...item, failed: true, preview: "[图片发送失败]" }
                : item));
            }
            throw error;
          }
        }}
        onLoadHistory={async () => {
          const currentMessages = messages[activeConversation.id] ?? [];
          const timestamps = currentMessages
            .map((message) => message.createdTime)
            .filter((value): value is number => typeof value === "number" && value > 0);
          const beforeTime = timestamps.length > 0 ? Math.min(...timestamps) : Date.now();
          const history = await fetchHistoryMessages(session, activeConversation.id, beforeTime);
          return applyServerMessagesRef.current(history, true, true);
        }}
      />
    );
  }

  return (
    <div
      className="main-shell"
      data-keyboard-visible={isKeyboardVisible ? "true" : "false"}
      style={{ "--shell-bottom-inset": `${bottomInset}px` } as CSSProperties}
    >
      <header className="main-header">
        <div>
          <span>ChatIM</span>
          <h1>{tab === "messages" ? "消息" : tab === "contacts" ? "联系人" : tab === "discover" ? "发现" : "我的"}</h1>
        </div>
        <div className="main-header-actions">
          {(tab === "messages" || tab === "contacts") ? (
            <button
              className="header-icon-button"
              type="button"
              onClick={() => {
                if (tab === "messages") switchTab("contacts");
                else setContactSurface({ kind: "search" });
              }}
              aria-label={tab === "messages" ? "发起会话" : "添加联系人"}
            >
              <PlusIcon />
            </button>
          ) : null}
          <button className="header-avatar" type="button" onClick={() => switchTab("profile")} aria-label="打开个人中心">
            {session.nickname.slice(0, 1).toUpperCase()}
          </button>
        </div>
      </header>

      <MobileScroll className="main-content-page">
        <main className="main-content">
          {tab === "messages" ? (
            <>
              <SearchField value={query} onChange={setQuery} placeholder="搜索会话或消息" />
              <section className="sync-strip" aria-label="同步状态">
                <span className={`connection-dot ${syncPresentation.tone}`} />
                <p>{syncPresentation.label}</p>
                <button
                  type="button"
                  className={syncState === "syncing" ? "spinning" : ""}
                  aria-label={connectionState === "connected" ? "重新同步" : "重新连接"}
                  onClick={() => {
                    if (connectionState === "connected") void syncOfflineRef.current?.();
                    else realtimeRef.current?.reconnectNow();
                  }}
                ><ReloadIcon /></button>
              </section>
              {conversations.length > 0 ? (
                <section className="conversation-list" aria-label="会话列表">
                  {filteredConversations.map((conversation) => (
                    <ConversationRow
                      key={conversation.id}
                      conversation={{
                        ...conversation,
                        draft: drafts[conversation.id]?.trim() || conversation.draft,
                      }}
                      onClick={() => openConversation(conversation.id)}
                    />
                  ))}
                  {filteredConversations.length === 0 ? <CompactEmpty text="没有找到相关会话" /> : null}
                </section>
              ) : (
                <section className="empty-conversation compact-empty-state">
                  <span><ChatBubbleIcon /></span>
                  <h2>会话还没有同步下来</h2>
                  <p>后端补齐会话摘要接口后，这里会显示最近聊天。</p>
                </section>
              )}
            </>
          ) : null}
          {tab === "contacts" ? (
            <>
              <SearchField value={query} onChange={setQuery} placeholder="搜索联系人" />
              {notice ? <p className="page-notice" role="status">{notice}</p> : null}
              <section className="contact-shortcuts" aria-label="通讯录入口">
                <ShortcutRow
                  icon={<BellIcon />}
                  tone="terracotta"
                  label="新的朋友"
                  meta={applicationUnread > 0 ? `${applicationUnread} 条待处理` : "暂无未读申请"}
                  badge={applicationUnread > 0 ? String(applicationUnread) : undefined}
                  onClick={() => setContactSurface({ kind: "applications" })}
                />
                <ShortcutRow
                  icon={<ChatBubbleIcon />}
                  tone="green"
                  label="群聊"
                  meta={`${groups.length} 个群聊`}
                  onClick={() => setContactSurface({ kind: "groups" })}
                />
              </section>
              <section className="contact-section">
                <div className="section-heading"><span>好友</span><small>{filteredContacts.length} 位</small></div>
                {filteredContacts.map((contact) => (
                  <button
                    className="contact-row"
                    type="button"
                    key={contact.id}
                    onClick={() => setContactSurface({ kind: "profile", contact, returnTo: "contacts" })}
                  >
                    <Avatar label={contact.avatar} tone={contact.avatarTone} online={contact.presence === "在线"} />
                    <span className="contact-copy"><strong>{contact.name}</strong><small>{contact.note}</small></span>
                    <ChevronRightIcon />
                  </button>
                ))}
                {filteredContacts.length === 0 ? <CompactEmpty text={contacts.length === 0 ? "还没有联系人，试试添加朋友" : "没有找到这位联系人"} /> : null}
              </section>
            </>
          ) : null}
          {tab === "discover" ? (
            <section className="feature-list" aria-label="发现功能">
              <FeatureRow icon={<BookmarkIcon />} tone="green" label="收藏" description="保存的重要消息与内容" />
              <FeatureRow icon={<FileIcon />} tone="blue" label="文件" description="聊天中的文件与图片" />
              <FeatureRow icon={<MagnifyingGlassIcon />} tone="terracotta" label="扫一扫" description="添加好友或登录桌面端" />
            </section>
          ) : null}
          {tab === "profile" ? (
            <>
              <section className="profile-hero">
                <div className="profile-avatar">{session.nickname.slice(0, 1).toUpperCase()}</div>
                <div>
                  <h2>{session.nickname}</h2>
                  <p>{session.email}</p>
                  <small>{session.description || "很高兴在 ChatIM 遇见你。"}</small>
                </div>
                <ChevronRightIcon />
              </section>
              <section className="profile-settings">
                <FeatureRow icon={<BellIcon />} tone="green" label="消息通知" description="声音、提醒与免打扰" />
                <FeatureRow icon={<GearIcon />} tone="blue" label="通用设置" description="聊天、存储与隐私" />
                <FeatureRow icon={<InfoCircledIcon />} tone="terracotta" label="账号与安全" description="登录设备和密码" />
              </section>
              <section className="environment-card">
                <span className={demoModeEnabled ? "online" : "waiting"} />
                <div><strong>{demoModeEnabled ? "演示环境" : "当前服务"}</strong><small>{demoModeEnabled ? "本地交互数据" : "http://localhost:10010"}</small></div>
              </section>
              <button type="button" className="secondary-button logout-button" onClick={() => void onLogout()}>
                <ExitIcon />
                退出登录
              </button>
            </>
          ) : null}
        </main>
      </MobileScroll>

      <nav className="bottom-nav" aria-label="主要导航">
        {tabs.map((item) => (
          <button
            key={item.id}
            type="button"
            className={tab === item.id ? "active" : ""}
            aria-current={tab === item.id ? "page" : undefined}
            onClick={() => switchTab(item.id)}
          >
            <span className="nav-icon-wrap">
              {item.icon}
              {item.id === "messages" && unreadTotal > 0 ? <b>{unreadTotal > 99 ? "99+" : unreadTotal}</b> : null}
              {item.id === "contacts" && applicationUnread > 0 ? <b>{applicationUnread > 99 ? "99+" : applicationUnread}</b> : null}
            </span>
            <span>{item.label}</span>
          </button>
        ))}
      </nav>
    </div>
  );
}

type TabId = "messages" | "contacts" | "discover" | "profile";
type AvatarTone = "green" | "blue" | "terracotta" | "gold" | "violet";
type MessageStatus = "sending" | "uploading" | "sent" | "unknown" | "failed";
type ContactSurface =
  | { kind: "search" }
  | { kind: "applications" }
  | { kind: "profile"; contact?: Contact; profile?: FriendProfile; returnTo: "search" | "contacts" }
  | { kind: "groups" }
  | { kind: "group-create" }
  | { kind: "group-result"; group: GroupRecord; failedIds: string[] }
  | { kind: "group-settings"; groupId: string }
  | { kind: "group-invite"; groupId: string }
  | null;

type Conversation = {
  id: string;
  name: string;
  avatar: string;
  avatarTone: AvatarTone;
  preview: string;
  time: string;
  unread: number;
  presence?: string;
  muted?: boolean;
  pinned?: boolean;
  failed?: boolean;
  draft?: string;
  group?: boolean;
  membersCount?: number;
  peerId?: string;
  lastMessageId?: string;
  lastReadMessageId?: string;
};

type GroupMember = {
  id: string;
  name: string;
  avatar: string;
  role: "owner" | "member";
};

type GroupRecord = {
  sessionId: string;
  name: string;
  avatar: string;
  avatarUrl?: string | null;
  creatorId: string;
  members: GroupMember[];
};

type ChatMessage = {
  id: string;
  messageId?: string;
  clientMessageId?: string;
  mine: boolean;
  content: string;
  time: string;
  createdTime?: number;
  status?: MessageStatus;
  kind?: "text" | "image";
  imageName?: string;
  imageWidth?: number;
  imageHeight?: number;
  imageSize?: number;
  uploadProgress?: number;
};

type Contact = {
  id: string;
  conversationId: string;
  name: string;
  avatar: string;
  avatarTone: AvatarTone;
  note: string;
  presence: string;
};

const demoConversations: Conversation[] = [
  { id: "c-chen", name: "陈知夏", avatar: "夏", avatarTone: "terracotta", preview: "照片收到了，周末见呀", time: "10:42", unread: 2, presence: "在线", pinned: true },
  { id: "c-studio", name: "山野摄影社", avatar: "山", avatarTone: "green", preview: "林一：我把路线发到群里了", time: "09:18", unread: 8, muted: true, group: true, membersCount: 5 },
  { id: "c-lin", name: "林一", avatar: "林", avatarTone: "blue", preview: "好，等你忙完再说", time: "昨天", unread: 0, presence: "12 分钟前在线" },
  { id: "c-family", name: "家人", avatar: "家", avatarTone: "gold", preview: "[图片]", time: "周三", unread: 0, muted: true, group: true, membersCount: 4 },
  { id: "c-muji", name: "木木", avatar: "木", avatarTone: "violet", preview: "这周的展览值得去看看", time: "周二", unread: 0, draft: "周六下午可以吗？", presence: "在线" },
];

const demoMessages: Record<string, ChatMessage[]> = {
  "c-chen": [
    { id: "m1", mine: false, content: "早呀，我把昨天拍的照片整理好啦。", time: "10:31" },
    { id: "m2", mine: true, content: "太好了，我刚好在找那张日落。", time: "10:35", status: "sent" },
    { id: "m3", mine: false, content: "照片收到了，周末见呀", time: "10:42" },
  ],
  "c-studio": [
    { id: "m4", mine: false, content: "周六上午九点在南门集合，记得带水。", time: "09:12" },
    { id: "m5", mine: false, content: "我把路线发到群里了，天气应该不错。", time: "09:18" },
  ],
  "c-lin": [
    { id: "m6", mine: true, content: "今天有点忙，晚点给你回电话。", time: "昨天 18:20", status: "sent" },
    { id: "m7", mine: false, content: "好，等你忙完再说", time: "昨天 18:23" },
  ],
  "c-family": [
    { id: "m8", mine: false, content: "周末回家吃饭吗？", time: "周三 20:05" },
    {
      id: "m8-image",
      mine: false,
      kind: "image",
      content: "/app-assets/login-connection-collage.png",
      imageName: "周末照片.jpg",
      imageWidth: 853,
      imageHeight: 1855,
      time: "周三 20:06",
    },
  ],
  "c-muji": [{ id: "m9", mine: false, content: "这周的展览值得去看看", time: "周二 14:16" }],
};

const demoContacts: Contact[] = [
  { id: "u-chen", conversationId: "c-chen", name: "陈知夏", avatar: "夏", avatarTone: "terracotta", note: "周末一起去看海", presence: "在线" },
  { id: "u-lin", conversationId: "c-lin", name: "林一", avatar: "林", avatarTone: "blue", note: "山野摄影社", presence: "12 分钟前在线" },
  { id: "u-muji", conversationId: "c-muji", name: "木木", avatar: "木", avatarTone: "violet", note: "常联系", presence: "在线" },
  { id: "u-qing", conversationId: "c-qing", name: "青禾", avatar: "青", avatarTone: "green", note: "旅行认识的朋友", presence: "昨天在线" },
  { id: "u-anan", conversationId: "c-anan", name: "安安", avatar: "安", avatarTone: "gold", note: "大学同学", presence: "3 小时前在线" },
];

const demoGroups: GroupRecord[] = [
  {
    sessionId: "c-studio",
    name: "山野摄影社",
    avatar: "山",
    creatorId: "demo-user-001",
    members: [
      { id: "demo-user-001", name: "旅行中的小鹿", avatar: "鹿", role: "owner" },
      { id: "u-chen", name: "陈知夏", avatar: "夏", role: "member" },
      { id: "u-lin", name: "林一", avatar: "林", role: "member" },
      { id: "u-muji", name: "木木", avatar: "木", role: "member" },
      { id: "u-qing", name: "青禾", avatar: "青", role: "member" },
    ],
  },
  {
    sessionId: "c-family",
    name: "家人",
    avatar: "家",
    creatorId: "demo-user-001",
    members: [
      { id: "demo-user-001", name: "旅行中的小鹿", avatar: "鹿", role: "owner" },
      { id: "family-1", name: "妈妈", avatar: "妈", role: "member" },
      { id: "family-2", name: "爸爸", avatar: "爸", role: "member" },
      { id: "family-3", name: "小雨", avatar: "雨", role: "member" },
    ],
  },
];

const demoApplications: FriendApplication[] = [
  { userId: "u-gunian", nickname: "顾念", avatar: null, msg: "你好，我们在山野摄影社见过", status: 0, time: "2026-09-19T09:42:00", isReceiver: 1 },
  { userId: "u-jiangyu", nickname: "江屿", avatar: null, msg: "我是林一的朋友，想认识一下", status: 0, time: "2026-09-18T20:16:00", isReceiver: 1 },
  { userId: "u-zhou", nickname: "周周", avatar: null, msg: "下次一起去看展吧", status: 1, time: "2026-09-17T16:08:00", isReceiver: 1 },
  { userId: "u-xiaobei", nickname: "小北", avatar: null, msg: "你好呀", status: 4, time: "2026-08-20T11:30:00", isReceiver: 1 },
];

function toneFromId(value: string): AvatarTone {
  const tones: AvatarTone[] = ["green", "blue", "terracotta", "gold", "violet"];
  const score = [...value].reduce((total, character) => total + character.charCodeAt(0), 0);
  return tones[score % tones.length];
}

function toContact(item: {
  userId: string;
  nickname: string;
  avatar?: string | null;
  status?: number;
  signature?: string | null;
  sessionId?: string | null;
}): Contact {
  return {
    id: item.userId,
    conversationId: item.sessionId || `c-${item.userId}`,
    name: item.nickname,
    avatar: item.nickname.slice(0, 1) || "友",
    avatarTone: toneFromId(item.userId),
    note: item.signature || "ChatIM 联系人",
    presence: "最近在线",
  };
}

function contactToProfile(contact: Contact): FriendProfile {
  return {
    userId: contact.id,
    nickname: contact.name,
    avatar: null,
    signature: contact.note,
    sessionId: contact.conversationId,
    status: 0,
  };
}

function SearchField({ value, onChange, placeholder }: { value: string; onChange: (value: string) => void; placeholder: string }) {
  return (
    <label className="content-search" data-scroll-drag="ignore">
      <MagnifyingGlassIcon aria-hidden="true" />
      <KeyboardInput aria-label={placeholder} value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} />
      {value ? <button type="button" onClick={() => onChange("")} aria-label="清空搜索"><Cross2Icon /></button> : null}
    </label>
  );
}

function Avatar({ label, tone, online = false }: { label: string; tone: AvatarTone; online?: boolean }) {
  return (
    <span className={`list-avatar ${tone}`} aria-hidden="true">
      {label}
      {online ? <i /> : null}
    </span>
  );
}

function ConversationRow({ conversation, onClick }: { conversation: Conversation; onClick: () => void }) {
  return (
    <button className="conversation-row" type="button" onClick={onClick}>
      <Avatar label={conversation.avatar} tone={conversation.avatarTone} online={conversation.presence === "在线"} />
      <span className="conversation-copy">
        <span className="conversation-title">
          <strong>{conversation.name}</strong>
          {conversation.pinned ? <small className="pin-label">置顶</small> : null}
        </span>
        <span className={`conversation-preview ${conversation.failed ? "failed" : ""}`}>
          {conversation.draft ? <em>草稿</em> : null}
          {conversation.failed ? "发送失败" : conversation.draft || conversation.preview}
        </span>
      </span>
      <span className="conversation-meta">
        <time>{conversation.time}</time>
        {conversation.unread > 0 ? <b>{conversation.unread > 99 ? "99+" : conversation.unread}</b> : conversation.muted ? <SpeakerOffIcon /> : null}
      </span>
    </button>
  );
}

function ShortcutRow({
  icon,
  tone,
  label,
  meta,
  badge,
  onClick,
}: {
  icon: ReactNode;
  tone: AvatarTone;
  label: string;
  meta: string;
  badge?: string;
  onClick?: () => void;
}) {
  return (
    <button className="shortcut-row" type="button" onClick={onClick}>
      <span className={`shortcut-icon ${tone}`}>{icon}{badge ? <b>{badge}</b> : null}</span>
      <span><strong>{label}</strong><small>{meta}</small></span>
      <ChevronRightIcon />
    </button>
  );
}

function FeatureRow({ icon, tone, label, description }: { icon: ReactNode; tone: AvatarTone; label: string; description: string }) {
  return (
    <button className="feature-row" type="button">
      <span className={`feature-icon ${tone}`}>{icon}</span>
      <span><strong>{label}</strong><small>{description}</small></span>
      <em>即将开放</em>
      <ChevronRightIcon />
    </button>
  );
}

function CompactEmpty({ text }: { text: string }) {
  return <div className="compact-empty"><MagnifyingGlassIcon /><span>{text}</span></div>;
}

function SubpageHeader({
  title,
  subtitle,
  onBack,
  action,
}: {
  title: string;
  subtitle?: string;
  onBack: () => void;
  action?: ReactNode;
}) {
  return (
    <header className="subpage-header">
      <button type="button" onClick={onBack} aria-label="返回"><ArrowLeftIcon /></button>
      <div><strong>{title}</strong>{subtitle ? <small>{subtitle}</small> : null}</div>
      <span className="subpage-header-action">{action}</span>
    </header>
  );
}

function FriendSearchScreen({
  session,
  onBack,
  onOpenProfile,
}: {
  session: AuthSession;
  onBack: () => void;
  onOpenProfile: (profile: FriendProfile) => void;
}) {
  const keyboard = useKeyboard();
  const [keyword, setKeyword] = useState("");
  const [result, setResult] = useState<FriendProfile | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const value = keyword.trim();
    if (!emailPattern.test(value) && !/^1\d{10}$/.test(value)) {
      setError("请输入完整的邮箱或 11 位手机号");
      setResult(null);
      return;
    }

    setBusy(true);
    setError("");
    setResult(null);
    try {
      const profile = await searchUser(session, value);
      keyboard.hide();
      setResult(profile);
    } catch (searchError) {
      setError(toContactErrorMessage(searchError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="subpage-screen">
      <SubpageHeader title="添加朋友" subtitle="通过邮箱或手机号查找" onBack={() => { keyboard.hide(); onBack(); }} />
      <MobileScroll className="subpage-scroll">
        <main className="subpage-content friend-search-content">
          <form className="friend-search-form" onSubmit={submit}>
            <label data-scroll-drag="ignore">
              <MagnifyingGlassIcon />
              <KeyboardInput
                aria-label="搜索用户"
                inputMode="email"
                value={keyword}
                onChange={(event) => {
                  setKeyword(event.target.value);
                  setError("");
                }}
                placeholder="邮箱或手机号"
              />
              {keyword ? <button type="button" onClick={() => { setKeyword(""); setResult(null); }} aria-label="清空"><Cross2Icon /></button> : null}
            </label>
            <button type="submit" className="search-submit" disabled={busy}>{busy ? "查找中" : "查找"}</button>
          </form>

          {demoModeEnabled ? (
            <div className="demo-search-tip">
              <InfoCircledIcon />
              <span>演示账号：suwan@chatim.cn 或 18800001111</span>
            </div>
          ) : null}

          {error ? <div className="search-feedback error" role="alert"><Cross2Icon /><span>{error}</span></div> : null}

          {result ? (
            <section className="search-result-card" aria-label="搜索结果">
              <Avatar label={result.nickname.slice(0, 1)} tone={toneFromId(result.userId)} />
              <div><strong>{result.nickname}</strong><small>{result.signature || result.email || result.phone || "ChatIM 用户"}</small></div>
              <button type="button" onClick={() => onOpenProfile(result)}>查看资料</button>
            </section>
          ) : !error ? (
            <section className="search-guide">
              <span><PersonIcon /></span>
              <h2>找到想联系的人</h2>
              <p>搜索结果只显示必要的公开资料，发送申请前可以先确认对方身份。</p>
            </section>
          ) : null}
        </main>
      </MobileScroll>
    </div>
  );
}

function FriendProfileScreen({
  session,
  initialProfile,
  shouldLoadDetail,
  onBack,
  onMessage,
}: {
  session: AuthSession;
  initialProfile: FriendProfile;
  shouldLoadDetail: boolean;
  onBack: () => void;
  onMessage: (profile: FriendProfile) => void;
}) {
  const keyboard = useKeyboard();
  const [profile, setProfile] = useState(initialProfile);
  const [loading, setLoading] = useState(shouldLoadDetail);
  const [showApplication, setShowApplication] = useState(false);
  const [applicationMessage, setApplicationMessage] = useState(`我是${session.nickname}`);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [sent, setSent] = useState(false);

  useEffect(() => {
    if (!shouldLoadDetail) return;
    let active = true;
    fetchFriendDetail(session, initialProfile.userId)
      .then((detail) => {
        if (active) setProfile(detail);
      })
      .catch((error) => {
        if (active) setFeedback(toContactErrorMessage(error));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [initialProfile.userId, session, shouldLoadDetail]);

  const submitApplication = async () => {
    const message = applicationMessage.trim();
    if (!message) {
      setFeedback("请填写一句申请说明");
      return;
    }
    setBusy(true);
    setFeedback("");
    try {
      await sendFriendRequest(session, profile.userId, message);
      keyboard.hide();
      setSent(true);
      setShowApplication(false);
      setFeedback("好友申请已发送，对方通过后就可以聊天");
    } catch (error) {
      setFeedback(toContactErrorMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const genderLabel = profile.gender === 0 ? "女" : profile.gender === 1 ? "男" : "未设置";

  return (
    <div className="subpage-screen">
      <SubpageHeader title="个人资料" subtitle={profile.status === 0 ? "联系人" : "搜索结果"} onBack={() => { keyboard.hide(); onBack(); }} />
      <MobileScroll className="subpage-scroll">
        <main className="subpage-content friend-profile-content">
          <section className="friend-profile-hero">
            <Avatar label={profile.nickname.slice(0, 1)} tone={toneFromId(profile.userId)} online={profile.status === 0} />
            <h1>{profile.nickname}</h1>
            <p>{profile.signature || "这个人很安静，还没有留下签名。"}</p>
            <span className={profile.status === 0 ? "friend-state connected" : "friend-state"}>
              {profile.status === 0 ? "已是好友" : sent ? "等待对方通过" : "还不是好友"}
            </span>
          </section>

          <section className="profile-detail-list">
            <div><span>邮箱</span><strong>{profile.email || "未公开"}</strong></div>
            <div><span>手机号</span><strong>{profile.phone ? `${profile.phone.slice(0, 3)}****${profile.phone.slice(-4)}` : "未公开"}</strong></div>
            <div><span>性别</span><strong>{genderLabel}</strong></div>
            <div><span>用户 ID</span><strong>{profile.userId}</strong></div>
          </section>

          {loading ? <p className="profile-loading"><span className="button-spinner" />正在同步资料</p> : null}
          {feedback ? <p className={`profile-feedback ${sent ? "success" : ""}`} role="status">{feedback}</p> : null}

          {showApplication ? (
            <section className="application-compose">
              <label htmlFor="friend-application-message">申请说明</label>
              <div data-scroll-drag="ignore">
                <KeyboardTextarea
                  id="friend-application-message"
                  aria-label="好友申请说明"
                  maxLength={80}
                  rows={3}
                  value={applicationMessage}
                  onChange={(event) => setApplicationMessage(event.target.value)}
                  placeholder="告诉对方你是谁"
                />
                <small>{applicationMessage.length}/80</small>
              </div>
              <button type="button" className="primary-button" disabled={busy} onClick={() => void submitApplication()}>
                {busy ? "正在发送" : "发送申请"}
              </button>
              <button type="button" className="plain-text-button" onClick={() => { keyboard.hide(); setShowApplication(false); }}>取消</button>
            </section>
          ) : profile.status === 0 ? (
            <button type="button" className="primary-button profile-main-action" onClick={() => onMessage(profile)}>
              <ChatBubbleIcon />发消息
            </button>
          ) : (
            <button type="button" className="primary-button profile-main-action" disabled={sent} onClick={() => setShowApplication(true)}>
              <PlusIcon />{sent ? "申请已发送" : "添加到联系人"}
            </button>
          )}
        </main>
      </MobileScroll>
    </div>
  );
}

function FriendApplicationsScreen({
  session,
  applications,
  onApplicationsChange,
  onBack,
  onAccepted,
  onMessage,
}: {
  session: AuthSession;
  applications: FriendApplication[];
  onApplicationsChange: (items: FriendApplication[]) => void;
  onBack: () => void;
  onAccepted: (application: FriendApplication, decision: ApplicationDecision | true) => void;
  onMessage: (application: FriendApplication) => void;
}) {
  const [busyId, setBusyId] = useState("");
  const [feedback, setFeedback] = useState("");
  const unreadIds = applications.filter((item) => item.isReceiver === 1 && item.status === 0).map((item) => item.userId);

  const markAllRead = async () => {
    if (unreadIds.length === 0) return;
    setBusyId("all");
    setFeedback("");
    try {
      await updateFriendApplications(session, 3, unreadIds);
      onApplicationsChange(applications.map((item) => item.status === 0 ? { ...item, status: 3 } : item));
      setFeedback("未读申请已全部标记为已读");
    } catch (error) {
      setFeedback(toContactErrorMessage(error));
    } finally {
      setBusyId("");
    }
  };

  const decide = async (application: FriendApplication, status: 1 | 2) => {
    setBusyId(application.userId);
    setFeedback("");
    try {
      const decision = await updateFriendApplications(session, status, [application.userId]);
      onApplicationsChange(applications.map((item) =>
        item.userId === application.userId ? { ...item, status } : item,
      ));
      if (status === 1) onAccepted(application, decision);
      setFeedback(status === 1 ? `你和${application.nickname}已经成为好友` : `已拒绝${application.nickname}的申请`);
    } catch (error) {
      setFeedback(toContactErrorMessage(error));
    } finally {
      setBusyId("");
    }
  };

  return (
    <div className="subpage-screen">
      <SubpageHeader
        title="新的朋友"
        subtitle={`${applications.length} 条申请`}
        onBack={onBack}
        action={<button type="button" disabled={unreadIds.length === 0 || busyId === "all"} onClick={() => void markAllRead()}>全部已读</button>}
      />
      <MobileScroll className="subpage-scroll">
        <main className="subpage-content application-list-content">
          {feedback ? <p className="application-feedback" role="status">{feedback}</p> : null}
          {applications.length === 0 ? (
            <section className="search-guide application-empty"><BellIcon /><h2>暂时没有新申请</h2><p>收到好友申请后会显示在这里。</p></section>
          ) : applications.map((application) => {
            const actionable = application.isReceiver === 1 && (application.status === 0 || application.status === 3);
            const busy = busyId === application.userId;
            return (
              <article className={`application-card status-${application.status}`} key={`${application.userId}-${application.time}`}>
                <div className="application-card-head">
                  <Avatar label={application.nickname.slice(0, 1)} tone={toneFromId(application.userId)} />
                  <div><strong>{application.nickname}</strong><small>{formatApplicationTime(application.time)}</small></div>
                  <span className="application-status">{applicationStatusLabel(application.status)}</span>
                </div>
                <p>{application.msg || "请求添加你为好友"}</p>
                {actionable ? (
                  <div className="application-actions">
                    <button type="button" className="reject" disabled={busy} onClick={() => void decide(application, 2)}>拒绝</button>
                    <button type="button" className="accept" disabled={busy} onClick={() => void decide(application, 1)}>{busy ? "处理中" : "接受"}</button>
                  </div>
                ) : application.status === 1 ? (
                  <button type="button" className="message-new-friend" onClick={() => onMessage(application)}><ChatBubbleIcon />发消息</button>
                ) : application.status === 4 ? (
                  <button type="button" className="message-new-friend muted" disabled>申请已过期</button>
                ) : null}
              </article>
            );
          })}
        </main>
      </MobileScroll>
    </div>
  );
}

function applicationStatusLabel(status: number) {
  return status === 0 ? "新申请" : status === 1 ? "已通过" : status === 2 ? "已拒绝" : status === 3 ? "待处理" : "已过期";
}

function formatApplicationTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const today = new Date();
  const sameDay = date.toDateString() === today.toDateString();
  return new Intl.DateTimeFormat("zh-CN", sameDay
    ? { hour: "2-digit", minute: "2-digit", hour12: false }
    : { month: "numeric", day: "numeric" }).format(date);
}

function GroupListScreen({
  groups,
  onBack,
  onCreate,
  onOpen,
}: {
  groups: GroupRecord[];
  onBack: () => void;
  onCreate: () => void;
  onOpen: (group: GroupRecord) => void;
}) {
  return (
    <div className="subpage-screen">
      <SubpageHeader
        title="群聊"
        subtitle={`${groups.length} 个群聊`}
        onBack={onBack}
        action={<button type="button" onClick={onCreate}>创建</button>}
      />
      <MobileScroll className="subpage-scroll">
        <main className="subpage-content group-list-content">
          <button type="button" className="create-group-card" onClick={onCreate}>
            <span><PlusIcon /></span>
            <div><strong>创建新的群聊</strong><small>选择好友，一起开始聊天</small></div>
            <ChevronRightIcon />
          </button>
          {groups.length > 0 ? (
            <section className="group-list" aria-label="群聊列表">
              {groups.map((group) => (
                <button type="button" className="group-list-row" key={group.sessionId} onClick={() => onOpen(group)}>
                  <Avatar label={group.avatar} tone="green" />
                  <span><strong>{group.name}</strong><small>{group.members.length} 位成员</small></span>
                  <span className="group-avatar-stack" aria-hidden="true">
                    {group.members.slice(0, 3).map((member) => <i key={member.id}>{member.avatar}</i>)}
                  </span>
                  <ChevronRightIcon />
                </button>
              ))}
            </section>
          ) : (
            <section className="search-guide group-empty"><ChatBubbleIcon /><h2>还没有群聊</h2><p>选择一位或多位好友，就能创建群聊。</p></section>
          )}
        </main>
      </MobileScroll>
    </div>
  );
}

type GroupMemberPickerProps = {
  session: AuthSession;
  contacts: Contact[];
  onBack: () => void;
} & (
  | {
      mode: "create";
      group?: never;
      onCreated: (result: CreateGroupResult, selectedContacts: Contact[]) => void;
      onInvited?: never;
    }
  | {
      mode: "invite";
      group: GroupRecord;
      onCreated?: never;
      onInvited: (result: InviteGroupResult, selectedContacts: Contact[]) => void;
    }
);

function GroupMemberPicker(props: GroupMemberPickerProps) {
  const [query, setQuery] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [inviteResult, setInviteResult] = useState<InviteGroupResult | null>(null);
  const [submittedContacts, setSubmittedContacts] = useState<Contact[]>([]);
  const selectedContacts = props.contacts.filter((contact) => selectedIds.includes(contact.id));
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const filteredContacts = props.contacts.filter((contact) =>
    `${contact.name} ${contact.note}`.toLocaleLowerCase().includes(normalizedQuery),
  );

  const toggle = (contactId: string) => {
    setSelectedIds((items) => items.includes(contactId)
      ? items.filter((id) => id !== contactId)
      : [...items, contactId]);
    setFeedback("");
  };

  const submit = async () => {
    if (selectedContacts.length === 0 || busy) return;
    setBusy(true);
    setFeedback("");
    try {
      if (props.mode === "create") {
        const result = await createGroup(props.session, selectedIds);
        props.onCreated(result, selectedContacts);
      } else {
        const result = await inviteGroupMembers(props.session, props.group.sessionId, selectedIds);
        setSubmittedContacts(selectedContacts);
        props.onInvited(result, selectedContacts);
        setInviteResult(result);
      }
    } catch (error) {
      setFeedback(toGroupErrorMessage(error));
    } finally {
      setBusy(false);
    }
  };

  if (props.mode === "invite" && inviteResult) {
    const successIds = new Set(inviteResult.successIds.map(String));
    const failedIds = new Set(inviteResult.failedIds.map(String));
    const resultContacts = submittedContacts.length > 0 ? submittedContacts : selectedContacts;
    return (
      <div className="subpage-screen">
        <SubpageHeader title="邀请结果" subtitle={props.group.name} onBack={props.onBack} />
        <MobileScroll className="subpage-scroll">
          <main className="subpage-content group-result-content">
            <section className="group-result-hero compact">
              <span className="result-check"><CheckIcon /></span>
              <h1>邀请已处理</h1>
              <p>{successIds.size} 位好友已加入，{failedIds.size} 位未能加入。</p>
            </section>
            {resultContacts.map((contact) => (
              <div className="invite-result-row" key={contact.id}>
                <Avatar label={contact.avatar} tone={contact.avatarTone} />
                <span><strong>{contact.name}</strong><small>{successIds.has(contact.id) ? "已加入群聊" : "邀请失败或已在群内"}</small></span>
                <b className={successIds.has(contact.id) ? "success" : "failed"}>{successIds.has(contact.id) ? "成功" : "未加入"}</b>
              </div>
            ))}
            <button type="button" className="group-primary-action" onClick={props.onBack}>返回群资料</button>
          </main>
        </MobileScroll>
      </div>
    );
  }

  return (
    <div className="subpage-screen">
      <SubpageHeader
        title={props.mode === "create" ? "创建群聊" : "邀请好友"}
        subtitle={selectedIds.length > 0 ? `已选择 ${selectedIds.length} 位` : "从好友中选择"}
        onBack={props.onBack}
        action={(
          <button type="button" disabled={selectedIds.length === 0 || busy} onClick={() => void submit()}>
            {busy ? "处理中" : props.mode === "create" ? `创建${selectedIds.length ? ` (${selectedIds.length})` : ""}` : `邀请${selectedIds.length ? ` (${selectedIds.length})` : ""}`}
          </button>
        )}
      />
      <MobileScroll className="subpage-scroll">
        <main className="subpage-content group-picker-content">
          <SearchField value={query} onChange={setQuery} placeholder="搜索好友" />
          {selectedContacts.length > 0 ? (
            <section className="selected-member-strip" aria-label="已选择成员">
              {selectedContacts.map((contact) => (
                <button type="button" key={contact.id} onClick={() => toggle(contact.id)} aria-label={`取消选择${contact.name}`}>
                  <Avatar label={contact.avatar} tone={contact.avatarTone} />
                  <small>{contact.name}</small>
                  <i><Cross2Icon /></i>
                </button>
              ))}
            </section>
          ) : (
            <p className="group-picker-tip">{props.mode === "create" ? "至少选择 1 位好友创建群聊" : "选择还未加入群聊的好友"}</p>
          )}
          {feedback ? <p className="application-feedback error" role="alert">{feedback}</p> : null}
          <section className="group-contact-picker" aria-label="好友选择列表">
            {filteredContacts.map((contact) => {
              const selected = selectedIds.includes(contact.id);
              return (
                <button
                  type="button"
                  className={selected ? "selected" : ""}
                  key={contact.id}
                  onClick={() => toggle(contact.id)}
                  aria-pressed={selected}
                >
                  <Avatar label={contact.avatar} tone={contact.avatarTone} online={contact.presence === "在线"} />
                  <span><strong>{contact.name}</strong><small>{contact.note}</small></span>
                  <i className="member-check">{selected ? <CheckIcon /> : null}</i>
                </button>
              );
            })}
            {filteredContacts.length === 0 ? <CompactEmpty text={props.contacts.length === 0 ? "没有可邀请的好友" : "没有找到这位好友"} /> : null}
          </section>
        </main>
      </MobileScroll>
    </div>
  );
}

function GroupCreateResultScreen({
  group,
  failedIds,
  contacts,
  onBack,
  onEnter,
}: {
  group: GroupRecord;
  failedIds: string[];
  contacts: Contact[];
  onBack: () => void;
  onEnter: () => void;
}) {
  const failed = contacts.filter((contact) => failedIds.includes(contact.id));
  return (
    <div className="subpage-screen">
      <SubpageHeader title="创建结果" subtitle="群聊已经准备好" onBack={onBack} />
      <MobileScroll className="subpage-scroll">
        <main className="subpage-content group-result-content">
          <section className="group-result-hero">
            <span className="group-result-avatar">{group.avatar}</span>
            <span className="result-check"><CheckIcon /></span>
            <h1>{group.name}</h1>
            <p>{group.members.length} 位成员已加入群聊</p>
            <div className="result-member-avatars">
              {group.members.slice(0, 6).map((member) => <i key={member.id}>{member.avatar}</i>)}
            </div>
          </section>
          {failed.length > 0 ? (
            <section className="partial-failure-card">
              <strong>{failed.length} 位好友未能加入</strong>
              <p>不影响其他成员使用群聊，稍后可以再次邀请。</p>
              <div>{failed.map((contact) => <span key={contact.id}>{contact.name}</span>)}</div>
            </section>
          ) : (
            <p className="group-all-success"><CheckIcon />所有选择的好友都已成功加入</p>
          )}
          <button type="button" className="group-primary-action" onClick={onEnter}>进入群聊</button>
          <button type="button" className="group-secondary-action" onClick={onBack}>返回群聊列表</button>
        </main>
      </MobileScroll>
    </div>
  );
}

function GroupSettingsScreen({
  group,
  onBack,
  onInvite,
}: {
  group: GroupRecord;
  onBack: () => void;
  onInvite: () => void;
}) {
  return (
    <div className="subpage-screen">
      <SubpageHeader title="群资料" subtitle={`${group.members.length} 位成员`} onBack={onBack} />
      <MobileScroll className="subpage-scroll">
        <main className="subpage-content group-settings-content">
          <section className="group-profile-card">
            <Avatar label={group.avatar} tone="green" />
            <div><h1>{group.name}</h1><p>群号 {group.sessionId}</p></div>
          </section>
          <section className="group-members-card">
            <div className="group-section-heading"><strong>群成员</strong><small>{group.members.length} 人</small></div>
            <div className="group-member-grid">
              {group.members.slice(0, 8).map((member) => (
                <span key={member.id}>
                  <Avatar label={member.avatar} tone={toneFromId(member.id)} />
                  <small>{member.name}</small>
                  {member.role === "owner" ? <b>群主</b> : null}
                </span>
              ))}
              <button type="button" onClick={onInvite} aria-label="邀请好友">
                <i><PlusIcon /></i><small>邀请</small>
              </button>
            </div>
          </section>
          <section className="group-info-list">
            <div><span><strong>群公告</strong><small>欢迎来到群聊，友善交流，一起分享生活。</small></span><ChevronRightIcon /></div>
            <div><span><strong>我在本群的昵称</strong><small>使用当前账号昵称</small></span><ChevronRightIcon /></div>
            <div><span><strong>消息免打扰</strong><small>跟随会话设置</small></span><ChevronRightIcon /></div>
          </section>
          <button type="button" className="group-primary-action invite-more-button" onClick={onInvite}><PlusIcon />邀请更多好友</button>
        </main>
      </MobileScroll>
    </div>
  );
}

function ChatScreen({
  conversation,
  messages,
  draft,
  onDraftChange,
  onBack,
  onOpenDetails,
  onSend,
  onSendImage,
  onLoadHistory,
  connectionLabel,
}: {
  conversation: Conversation;
  messages: ChatMessage[];
  draft: string;
  onDraftChange: (value: string) => void;
  onBack: () => void;
  onOpenDetails: () => void;
  onSend: (content: string) => void;
  onSendImage: (file: File) => Promise<void>;
  onLoadHistory: () => Promise<number>;
  connectionLabel: string;
}) {
  const keyboard = useKeyboard();
  const { bottomInset, isKeyboardVisible } = useKeyboardInsets();
  const imageInputRef = useRef<HTMLInputElement>(null);
  const [showTools, setShowTools] = useState(false);
  const [toast, setToast] = useState("");
  const [imageBusy, setImageBusy] = useState(false);
  const [previewImage, setPreviewImage] = useState<ChatMessage | null>(null);
  const [historyBusy, setHistoryBusy] = useState(false);

  useEffect(() => {
    if (isKeyboardVisible) setShowTools(false);
  }, [isKeyboardVisible]);

  const submit = () => {
    const content = draft.trim();
    if (!content) return;
    onSend(content);
  };

  const showComingSoon = (label: string) => {
    keyboard.hide();
    setShowTools(false);
    setToast(`${label}将在后续联调中开放`);
    window.setTimeout(() => setToast(""), 1_800);
  };

  const selectImage = () => {
    keyboard.hide();
    setShowTools(false);
    imageInputRef.current?.click();
  };

  const handleImage = async (file?: File) => {
    if (!file || imageBusy) return;
    setImageBusy(true);
    setToast("正在处理并上传图片…");
    try {
      await onSendImage(file);
      setToast("图片已加入会话");
    } catch (error) {
      setToast(toMediaErrorMessage(error));
    } finally {
      setImageBusy(false);
      if (imageInputRef.current) imageInputRef.current.value = "";
      window.setTimeout(() => setToast(""), 2_200);
    }
  };

  const loadHistory = async () => {
    if (historyBusy) return;
    setHistoryBusy(true);
    try {
      const count = await onLoadHistory();
      setToast(count > 0 ? `已补充 ${count} 条更早消息` : "没有更早的消息了");
    } catch (error) {
      setToast(toRealtimeErrorMessage(error));
    } finally {
      setHistoryBusy(false);
      window.setTimeout(() => setToast(""), 2_200);
    }
  };

  return (
    <div className="chat-screen" style={{ "--chat-bottom-inset": `${bottomInset}px` } as CSSProperties}>
      <header className="chat-header">
        <button type="button" className="chat-header-button" onClick={onBack} aria-label="返回消息列表"><ArrowLeftIcon /></button>
        <button type="button" className="chat-person" aria-label={`查看${conversation.name}的资料`}>
          <Avatar label={conversation.avatar} tone={conversation.avatarTone} online={conversation.presence === "在线"} />
          <span><strong>{conversation.name}</strong><small>{conversation.group ? `${conversation.membersCount || "多"} 位成员 · ${connectionLabel}` : conversation.presence || connectionLabel}</small></span>
        </button>
        <button
          type="button"
          className="chat-header-button"
          onClick={() => conversation.group ? onOpenDetails() : showComingSoon("会话设置")}
          aria-label={conversation.group ? "群资料" : "会话设置"}
        ><DotsHorizontalIcon /></button>
      </header>

      <MobileScroll className="chat-message-scroll">
        <main className="chat-timeline">
          {messages.length > 0 ? <button type="button" className="history-pill" onClick={() => void loadHistory()} disabled={historyBusy}>{historyBusy ? "正在加载…" : "查看更早消息"}</button> : null}
          <div className="time-divider"><span>今天</span></div>
          {messages.length === 0 ? (
            <div className="chat-empty"><ChatBubbleIcon /><strong>{conversation.group ? "群聊已经创建" : "你们已经是好友了"}</strong><span>发一条消息开始聊天吧</span></div>
          ) : messages.map((message) => (
            <div className={`message-line ${message.mine ? "mine" : "theirs"}`} key={message.id}>
              {!message.mine ? <Avatar label={conversation.avatar} tone={conversation.avatarTone} /> : null}
              <div className="message-stack">
                {message.kind === "image" ? (
                  <button
                    type="button"
                    className={`image-message-bubble ${message.status === "failed" ? "failed" : ""}`}
                    onClick={() => {
                      keyboard.hide();
                      setPreviewImage(message);
                    }}
                    aria-label={`查看图片${message.imageName ? `：${message.imageName}` : ""}`}
                  >
                    <img src={message.content} alt={message.imageName || "聊天图片"} draggable="false" />
                    {message.status === "uploading" ? (
                      <span className="image-upload-overlay"><i /><b>{message.uploadProgress || 0}%</b></span>
                    ) : null}
                    {message.status === "failed" ? <span className="image-failed-overlay">上传失败<br />请重新选择</span> : null}
                  </button>
                ) : (
                  <div className="message-bubble">{message.content}</div>
                )}
                <div className={`message-foot ${message.status || ""}`}>
                  <time>{message.time}</time>
                  {message.status === "sending" ? <><i />发送中</> : null}
                  {message.status === "uploading" ? <><i />上传中 {message.uploadProgress || 0}%</> : null}
                  {message.status === "sent" ? <><CheckIcon />已发送</> : null}
                  {message.status === "unknown" ? <>结果待确认</> : null}
                  {message.status === "failed" ? <>发送失败</> : null}
                </div>
              </div>
            </div>
          ))}
          <div className="timeline-end-space" />
        </main>
      </MobileScroll>

      {toast ? <div className="chat-toast" role="status">{toast}</div> : null}

      {previewImage ? (
        <div className="image-preview-screen" role="dialog" aria-modal="true" aria-label="图片预览">
          <button type="button" className="image-preview-close" onClick={() => setPreviewImage(null)} aria-label="关闭图片预览"><Cross2Icon /></button>
          <img src={previewImage.content} alt={previewImage.imageName || "聊天图片预览"} draggable="false" />
          <div className="image-preview-meta">
            <strong>{previewImage.imageName || "聊天图片"}</strong>
            <small>{formatImageMeta(previewImage)}</small>
          </div>
        </div>
      ) : null}

      {showTools ? (
        <div className="chat-tool-tray" style={{ bottom: bottomInset + 68 }}>
          <button type="button" onClick={selectImage} disabled={imageBusy} aria-label="选择图片"><span><ImageIcon /></span>{imageBusy ? "处理中" : "图片"}</button>
          <button type="button" onClick={() => showComingSoon("红包")}><span className="packet-symbol">¥</span>红包</button>
          <button type="button" onClick={() => showComingSoon("文件发送")}><span><FileIcon /></span>文件</button>
        </div>
      ) : null}

      <input
        ref={imageInputRef}
        className="chat-image-input"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        tabIndex={-1}
        onChange={(event) => void handleImage(event.target.files?.[0])}
      />

      <footer className="chat-composer" style={{ bottom: bottomInset }}>
        <button type="button" className="composer-tool" onClick={() => showComingSoon("表情")} aria-label="选择表情"><FaceIcon /></button>
        <div className="composer-input" data-scroll-drag="ignore">
          <KeyboardTextarea
            aria-label="输入消息"
            rows={1}
            maxLength={1000}
            placeholder="输入消息"
            value={draft}
            onChange={(event) => onDraftChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                submit();
              }
            }}
          />
        </div>
        {draft.trim() ? (
          <button type="button" className="send-message-button" onClick={submit} aria-label="发送消息"><PaperPlaneIcon /></button>
        ) : (
          <button
            type="button"
            className={`composer-tool ${showTools ? "active" : ""}`}
            onClick={() => {
              keyboard.hide();
              setShowTools((value) => !value);
            }}
            aria-label="更多发送方式"
          ><PlusIcon /></button>
        )}
      </footer>
    </div>
  );
}

const DRAFT_STORAGE_PREFIX = "chatim.chat.drafts.v1";
const CONVERSATION_STORAGE_PREFIX = "chatim.chat.conversations.v1";
const MESSAGE_STORAGE_PREFIX = "chatim.chat.messages.v1";
const CONTACT_STORAGE_PREFIX = "chatim.contacts.v1";
const APPLICATION_STORAGE_PREFIX = "chatim.friend-applications.v1";
const GROUP_STORAGE_PREFIX = "chatim.groups.v1";

function loadDrafts(userId: AuthSession["userId"]): Record<string, string> {
  try {
    return JSON.parse(window.localStorage.getItem(`${DRAFT_STORAGE_PREFIX}.${userId}`) || "{}") as Record<string, string>;
  } catch {
    return {};
  }
}

function saveDrafts(userId: AuthSession["userId"], drafts: Record<string, string>) {
  window.localStorage.setItem(`${DRAFT_STORAGE_PREFIX}.${userId}`, JSON.stringify(drafts));
}

function loadConversations(userId: AuthSession["userId"]): Conversation[] {
  try {
    const stored = JSON.parse(
      window.localStorage.getItem(`${CONVERSATION_STORAGE_PREFIX}.${userId}`) || "null",
    ) as Conversation[] | null;
    if (Array.isArray(stored)) return stored;
  } catch {
    // Fall through to the environment seed when the local cache is damaged.
  }
  return demoModeEnabled ? demoConversations : [];
}

function loadMessages(userId: AuthSession["userId"]): Record<string, ChatMessage[]> {
  try {
    const stored = JSON.parse(
      window.localStorage.getItem(`${MESSAGE_STORAGE_PREFIX}.${userId}`) || "null",
    ) as Record<string, ChatMessage[]> | null;
    if (stored && typeof stored === "object" && !Array.isArray(stored)) return stored;
  } catch {
    // Fall through to the environment seed when the local cache is damaged.
  }
  return demoModeEnabled ? demoMessages : {};
}

function saveChatState(
  userId: AuthSession["userId"],
  conversations: Conversation[],
  messages: Record<string, ChatMessage[]>,
) {
  window.localStorage.setItem(`${CONVERSATION_STORAGE_PREFIX}.${userId}`, JSON.stringify(conversations));
  saveMessages(userId, messages);
}

function saveMessages(
  userId: AuthSession["userId"],
  messages: Record<string, ChatMessage[]>,
) {
  window.localStorage.setItem(`${MESSAGE_STORAGE_PREFIX}.${userId}`, JSON.stringify(messages));
}

function loadContacts(userId: AuthSession["userId"]): Contact[] {
  try {
    const stored = JSON.parse(
      window.localStorage.getItem(`${CONTACT_STORAGE_PREFIX}.${userId}`) || "null",
    ) as Contact[] | null;
    if (Array.isArray(stored)) return stored;
  } catch {
    // Fall through to the demo seed or an empty real cache.
  }
  return demoModeEnabled ? demoContacts : [];
}

function loadApplications(userId: AuthSession["userId"]): FriendApplication[] {
  try {
    const stored = JSON.parse(
      window.localStorage.getItem(`${APPLICATION_STORAGE_PREFIX}.${userId}`) || "null",
    ) as FriendApplication[] | null;
    if (Array.isArray(stored)) return stored;
  } catch {
    // Fall through to the demo seed or an empty real cache.
  }
  return demoModeEnabled ? demoApplications : [];
}

function saveContactState(
  userId: AuthSession["userId"],
  contacts: Contact[],
  applications: FriendApplication[],
) {
  window.localStorage.setItem(`${CONTACT_STORAGE_PREFIX}.${userId}`, JSON.stringify(contacts));
  window.localStorage.setItem(`${APPLICATION_STORAGE_PREFIX}.${userId}`, JSON.stringify(applications));
}

function loadGroups(userId: AuthSession["userId"]): GroupRecord[] {
  try {
    const stored = JSON.parse(
      window.localStorage.getItem(`${GROUP_STORAGE_PREFIX}.${userId}`) || "null",
    ) as GroupRecord[] | null;
    if (Array.isArray(stored)) return stored;
  } catch {
    // Fall through to the demo seed or an empty real cache.
  }
  return demoModeEnabled ? demoGroups : [];
}

function saveGroups(userId: AuthSession["userId"], groups: GroupRecord[]) {
  window.localStorage.setItem(`${GROUP_STORAGE_PREFIX}.${userId}`, JSON.stringify(groups));
}

function makeClientMessageId() {
  return typeof crypto.randomUUID === "function"
    ? `client-${crypto.randomUUID()}`
    : `client-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function formatClock(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
}

function formatImageMeta(message: ChatMessage) {
  const dimensions = message.imageWidth && message.imageHeight ? `${message.imageWidth} × ${message.imageHeight}` : "原图";
  if (!message.imageSize) return dimensions;
  const size = message.imageSize >= 1024 * 1024
    ? `${(message.imageSize / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(message.imageSize / 1024))} KB`;
  return `${dimensions} · ${size}`;
}

function collectServerMessageKeys(messages: Record<string, ChatMessage[]>) {
  const keys = new Set<string>();
  Object.values(messages).flat().forEach((message) => {
    if (message.messageId) keys.add(`message:${message.messageId}`);
    if (message.messageId && message.clientMessageId) keys.add(`client:${message.clientMessageId}`);
  });
  return keys;
}

function isRealtimeMessage(message: RealtimeMessage) {
  return message
    && message.sessionId != null
    && message.senderId != null
    && typeof message.type === "number"
    && typeof message.body?.content === "string";
}

function toChatMessage(message: RealtimeMessage, userId: AuthSession["userId"]): ChatMessage {
  const createdTime = message.createdTime || Date.now();
  const clientMessageId = message.clientMessageId || undefined;
  return {
    id: clientMessageId || (message.messageId == null ? `server-${createdTime}` : `server-${message.messageId}`),
    messageId: message.messageId == null ? undefined : String(message.messageId),
    clientMessageId,
    mine: String(message.senderId) === String(userId),
    content: message.body.content,
    time: formatClock(new Date(createdTime)),
    createdTime,
    status: String(message.senderId) === String(userId) ? "sent" : undefined,
    kind: message.type === 1 ? "image" : "text",
    imageName: message.type === 1 ? imageNameFromUrl(message.body.content) : undefined,
  };
}

function compareChatMessages(left: ChatMessage, right: ChatMessage) {
  return (left.createdTime || 0) - (right.createdTime || 0);
}

function realtimePreview(message: RealtimeMessage) {
  if (message.type === 1) return "[图片]";
  if (message.type === 2) return "[表情]";
  if (message.type === 3) return message.body.redPacketWrapperText || "[红包]";
  return message.body.content;
}

function imageNameFromUrl(value: string) {
  try {
    const pathname = new URL(value, window.location.href).pathname;
    return decodeURIComponent(pathname.split("/").pop() || "聊天图片");
  } catch {
    return "聊天图片";
  }
}

function formatConversationTime(value?: number) {
  if (!value) return "刚刚";
  const date = new Date(value);
  const now = new Date();
  if (now.toDateString() === date.toDateString()) return formatClock(date);
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric" }).format(date);
}

function describeSyncState(
  connection: RealtimeConnectionState,
  sync: "idle" | "syncing" | "synced" | "failed",
  lastSyncTime: string,
) {
  if (connection === "auth-failed") {
    return { tone: "offline", label: "消息连接认证失败，请重新登录", chatLabel: "连接失效" };
  }
  if (connection === "reconnecting") {
    return { tone: "waiting", label: "连接中断，正在自动重连…", chatLabel: "重连中" };
  }
  if (connection === "connecting") {
    return { tone: "waiting", label: "正在连接消息服务…", chatLabel: "连接中" };
  }
  if (connection === "disconnected") {
    return { tone: "offline", label: "消息服务未连接，点击重试", chatLabel: "未连接" };
  }
  if (sync === "syncing") {
    return { tone: "online", label: "已连接，正在补齐离线消息…", chatLabel: "同步中" };
  }
  if (sync === "failed") {
    return { tone: "waiting", label: "已连接，离线消息同步失败，可点击重试", chatLabel: "同步待重试" };
  }
  return {
    tone: "online",
    label: lastSyncTime ? `已连接 · ${lastSyncTime} 已同步` : "已连接 · 消息已同步",
    chatLabel: "已同步",
  };
}

function toErrorMessage(error: unknown) {
  if (error instanceof AuthApiError || error instanceof Error) return error.message;
  return "操作没有完成，请稍后重试";
}

function toContactErrorMessage(error: unknown) {
  if (error instanceof ContactApiError || error instanceof Error) return error.message;
  return "联系人操作没有完成，请稍后重试";
}

function toGroupErrorMessage(error: unknown) {
  if (error instanceof GroupApiError || error instanceof Error) return error.message;
  return "群聊操作没有完成，请稍后重试";
}

function toMediaErrorMessage(error: unknown) {
  if (error instanceof MediaApiError || error instanceof Error) return error.message;
  return "图片没有发送成功，请稍后重试";
}

function toRealtimeErrorMessage(error: unknown) {
  if (error instanceof RealtimeApiError || error instanceof Error) return error.message;
  return "消息暂时无法同步，请稍后重试";
}
