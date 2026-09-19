import {
  type CSSProperties,
  type FormEvent,
  type ReactNode,
  useEffect,
  useMemo,
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
  const [contactSurface, setContactSurface] = useState<ContactSurface>(null);
  const [notice, setNotice] = useState("");

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
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const filteredConversations = conversations.filter((item) =>
    `${item.name} ${item.preview}`.toLocaleLowerCase().includes(normalizedQuery),
  );
  const filteredContacts = contacts.filter((item) =>
    `${item.name} ${item.note}`.toLocaleLowerCase().includes(normalizedQuery),
  );
  const applicationUnread = applications.filter((item) => item.isReceiver === 1 && item.status === 0).length;

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

  const openConversation = (conversationId: string) => {
    keyboard.hide();
    setConversations((items) =>
      items.map((item) => item.id === conversationId ? { ...item, unread: 0 } : item),
    );
    setActiveConversationId(conversationId);
  };

  const openContactConversation = (contact: Contact) => {
    setContactSurface(null);
    const existing = conversations.find((item) => item.id === contact.conversationId);
    if (existing) {
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

  if (activeConversation) {
    return (
      <ChatScreen
        conversation={activeConversation}
        messages={messages[activeConversation.id] ?? []}
        draft={drafts[activeConversation.id] ?? activeConversation.draft ?? ""}
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
            mine: true,
            content,
            time: formatClock(sentAt),
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

          window.setTimeout(() => {
            setMessages((items) => ({
              ...items,
              [activeConversation.id]: (items[activeConversation.id] ?? []).map((item) =>
                item.id === clientMessageId
                  ? { ...item, status: demoModeEnabled ? "sent" : "unknown" }
                  : item,
              ),
            }));
          }, 760);
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
                <span className={`connection-dot ${demoModeEnabled ? "online" : "waiting"}`} />
                <p>{demoModeEnabled ? "已连接 · 消息刚刚同步" : "等待会话摘要接口 · 登录状态正常"}</p>
                <button type="button" aria-label="重新同步"><ReloadIcon /></button>
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
                <ShortcutRow icon={<ChatBubbleIcon />} tone="green" label="群聊" meta="3 个群聊" onClick={() => setNotice("群聊创建将在下一轮接入")} />
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
type MessageStatus = "sending" | "sent" | "unknown" | "failed";
type ContactSurface =
  | { kind: "search" }
  | { kind: "applications" }
  | { kind: "profile"; contact?: Contact; profile?: FriendProfile; returnTo: "search" | "contacts" }
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
};

type ChatMessage = {
  id: string;
  mine: boolean;
  content: string;
  time: string;
  status?: MessageStatus;
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
  { id: "c-studio", name: "山野摄影社", avatar: "山", avatarTone: "green", preview: "林一：我把路线发到群里了", time: "09:18", unread: 8, muted: true, group: true },
  { id: "c-lin", name: "林一", avatar: "林", avatarTone: "blue", preview: "好，等你忙完再说", time: "昨天", unread: 0, presence: "12 分钟前在线" },
  { id: "c-family", name: "家人", avatar: "家", avatarTone: "gold", preview: "[图片]", time: "周三", unread: 0, muted: true, group: true },
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
  "c-family": [{ id: "m8", mine: false, content: "周末回家吃饭吗？", time: "周三 20:05" }],
  "c-muji": [{ id: "m9", mine: false, content: "这周的展览值得去看看", time: "周二 14:16" }],
};

const demoContacts: Contact[] = [
  { id: "u-chen", conversationId: "c-chen", name: "陈知夏", avatar: "夏", avatarTone: "terracotta", note: "周末一起去看海", presence: "在线" },
  { id: "u-lin", conversationId: "c-lin", name: "林一", avatar: "林", avatarTone: "blue", note: "山野摄影社", presence: "12 分钟前在线" },
  { id: "u-muji", conversationId: "c-muji", name: "木木", avatar: "木", avatarTone: "violet", note: "常联系", presence: "在线" },
  { id: "u-qing", conversationId: "c-qing", name: "青禾", avatar: "青", avatarTone: "green", note: "旅行认识的朋友", presence: "昨天在线" },
  { id: "u-anan", conversationId: "c-anan", name: "安安", avatar: "安", avatarTone: "gold", note: "大学同学", presence: "3 小时前在线" },
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

function ChatScreen({
  conversation,
  messages,
  draft,
  onDraftChange,
  onBack,
  onSend,
}: {
  conversation: Conversation;
  messages: ChatMessage[];
  draft: string;
  onDraftChange: (value: string) => void;
  onBack: () => void;
  onSend: (content: string) => void;
}) {
  const keyboard = useKeyboard();
  const { bottomInset, isKeyboardVisible } = useKeyboardInsets();
  const [showTools, setShowTools] = useState(false);
  const [toast, setToast] = useState("");

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

  return (
    <div className="chat-screen" style={{ "--chat-bottom-inset": `${bottomInset}px` } as CSSProperties}>
      <header className="chat-header">
        <button type="button" className="chat-header-button" onClick={onBack} aria-label="返回消息列表"><ArrowLeftIcon /></button>
        <button type="button" className="chat-person" aria-label={`查看${conversation.name}的资料`}>
          <Avatar label={conversation.avatar} tone={conversation.avatarTone} online={conversation.presence === "在线"} />
          <span><strong>{conversation.name}</strong><small>{conversation.group ? "5 位成员 · 消息已同步" : conversation.presence || "离线"}</small></span>
        </button>
        <button type="button" className="chat-header-button" onClick={() => showComingSoon("会话设置")} aria-label="会话设置"><DotsHorizontalIcon /></button>
      </header>

      <MobileScroll className="chat-message-scroll">
        <main className="chat-timeline">
          {messages.length > 0 ? <button type="button" className="history-pill" onClick={() => showComingSoon("更早的历史消息")}>查看更早消息</button> : null}
          <div className="time-divider"><span>今天</span></div>
          {messages.length === 0 ? (
            <div className="chat-empty"><ChatBubbleIcon /><strong>你们已经是好友了</strong><span>发一条消息开始聊天吧</span></div>
          ) : messages.map((message) => (
            <div className={`message-line ${message.mine ? "mine" : "theirs"}`} key={message.id}>
              {!message.mine ? <Avatar label={conversation.avatar} tone={conversation.avatarTone} /> : null}
              <div className="message-stack">
                <div className="message-bubble">{message.content}</div>
                <div className={`message-foot ${message.status || ""}`}>
                  <time>{message.time}</time>
                  {message.status === "sending" ? <><i />发送中</> : null}
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

      {showTools ? (
        <div className="chat-tool-tray" style={{ bottom: bottomInset + 68 }}>
          <button type="button" onClick={() => showComingSoon("图片发送")}><span><ImageIcon /></span>图片</button>
          <button type="button" onClick={() => showComingSoon("红包")}><span className="packet-symbol">¥</span>红包</button>
          <button type="button" onClick={() => showComingSoon("文件发送")}><span><FileIcon /></span>文件</button>
        </div>
      ) : null}

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

function makeClientMessageId() {
  return typeof crypto.randomUUID === "function"
    ? `client-${crypto.randomUUID()}`
    : `client-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function formatClock(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
}

function toErrorMessage(error: unknown) {
  if (error instanceof AuthApiError || error instanceof Error) return error.message;
  return "操作没有完成，请稍后重试";
}

function toContactErrorMessage(error: unknown) {
  if (error instanceof ContactApiError || error instanceof Error) return error.message;
  return "联系人操作没有完成，请稍后重试";
}
