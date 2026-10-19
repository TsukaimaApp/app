/* @ds-bundle: {"format":4,"namespace":"TsukaimaDesignSystem_17565f","components":[{"name":"ApprovalCard","sourcePath":"components/chat/ApprovalCard.jsx"},{"name":"AssistantMessage","sourcePath":"components/chat/AssistantMessage.jsx"},{"name":"CodeBlock","sourcePath":"components/chat/CodeBlock.jsx"},{"name":"Composer","sourcePath":"components/chat/Composer.jsx"},{"name":"PairingCard","sourcePath":"components/chat/PairingCard.jsx"},{"name":"THINKING_FLAIR","sourcePath":"components/chat/StatusLine.jsx"},{"name":"THINKING_FLAIR_WATCHLIST","sourcePath":"components/chat/StatusLine.jsx"},{"name":"StatusLine","sourcePath":"components/chat/StatusLine.jsx"},{"name":"ToolRow","sourcePath":"components/chat/ToolRow.jsx"},{"name":"UserBubble","sourcePath":"components/chat/UserBubble.jsx"},{"name":"ConversationItem","sourcePath":"components/chrome/ConversationItem.jsx"},{"name":"Drawer","sourcePath":"components/chrome/Drawer.jsx"},{"name":"DrawerLabel","sourcePath":"components/chrome/Drawer.jsx"},{"name":"TopBar","sourcePath":"components/chrome/TopBar.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"Card","sourcePath":"components/core/Card.jsx"},{"name":"Chip","sourcePath":"components/core/Chip.jsx"},{"name":"Dialog","sourcePath":"components/core/Dialog.jsx"},{"name":"Icon","sourcePath":"components/core/Icon.jsx"},{"name":"IconButton","sourcePath":"components/core/IconButton.jsx"},{"name":"Menu","sourcePath":"components/core/Menu.jsx"},{"name":"Row","sourcePath":"components/core/Row.jsx"},{"name":"Skeleton","sourcePath":"components/core/Skeleton.jsx"},{"name":"Switch","sourcePath":"components/core/Switch.jsx"},{"name":"TextField","sourcePath":"components/core/TextField.jsx"}],"sourceHashes":{"components/chat/ApprovalCard.jsx":"b36743c1f3a1","components/chat/AssistantMessage.jsx":"d76864673eaa","components/chat/CodeBlock.jsx":"5c55d25eb764","components/chat/Composer.jsx":"1169ed6cc7e2","components/chat/PairingCard.jsx":"57032b701515","components/chat/StatusLine.jsx":"079493c25ea0","components/chat/ToolRow.jsx":"b5cebd882336","components/chat/UserBubble.jsx":"0737861ca6b6","components/chrome/ConversationItem.jsx":"e9696b766fb7","components/chrome/Drawer.jsx":"4f63302ce2ec","components/chrome/TopBar.jsx":"f11edaeb28c3","components/core/Button.jsx":"e1eff801f46e","components/core/Card.jsx":"8d1b34e9e2d2","components/core/Chip.jsx":"9b6a139859ed","components/core/Dialog.jsx":"902ad0450858","components/core/Icon.jsx":"d1d0280f8c07","components/core/IconButton.jsx":"941f6730c450","components/core/Menu.jsx":"f6374926567f","components/core/Row.jsx":"89a6f53573ef","components/core/Skeleton.jsx":"05f57d1c330a","components/core/Switch.jsx":"0b412eb47778","components/core/TextField.jsx":"730eb666fea6","ui_kits/tsukaima/app.jsx":"e52e0791580e","ui_kits/tsukaima/chat-screen.jsx":"cbe639d2c2e7","ui_kits/tsukaima/cron-screen.jsx":"72e6c82dbff8","ui_kits/tsukaima/drawer-overlay.jsx":"572c8072bfc0","ui_kits/tsukaima/logs-screen.jsx":"2fa6060708dd","ui_kits/tsukaima/onboarding-screen.jsx":"3e109f048f6f","ui_kits/tsukaima/settings-screen.jsx":"6af8a44d4c6c","ui_kits/tsukaima/status-screen.jsx":"83d0c2b4ba06"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.TsukaimaDesignSystem_17565f = window.TsukaimaDesignSystem_17565f || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/chat/AssistantMessage.jsx
try { (() => {
function AssistantMessage({
  children,
  streaming = false,
  style
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '4px var(--gutter-page)',
      font: 'var(--type-body)',
      color: 'var(--text-body)',
      maxWidth: 640,
      ...style
    }
  }, children, streaming && /*#__PURE__*/React.createElement("span", {
    "aria-hidden": "true",
    style: {
      display: 'inline-block',
      width: 2,
      height: '1em',
      background: 'var(--accent)',
      marginLeft: 2,
      verticalAlign: '-0.15em',
      animation: 'tsk-caret 1s steps(2) infinite'
    }
  }, /*#__PURE__*/React.createElement("style", null, '@keyframes tsk-caret{0%,100%{opacity:1}50%{opacity:0}}')));
}
Object.assign(__ds_scope, { AssistantMessage });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/AssistantMessage.jsx", error: String((e && e.message) || e) }); }

// components/chat/StatusLine.jsx
try { (() => {
const {
  useEffect,
  useState
} = React;
const THINKING_FLAIR = ['Thinking…', 'Summoning…', 'Charging ki…', 'Powering up…', 'Channeling…', 'Reading the scroll…', 'Consulting the shrine…', 'Steeping matcha…', 'Folding cranes…', 'Sharpening the blade…', 'Drawing the seal…', 'Chanting…', 'Gathering spirit energy…', 'Training arc…', 'Unlocking the next form…', 'Meditating under the waterfall…', 'Rolling the gacha…', 'Inking the next panel…', 'Grinding XP…', 'Binding the contract…'];
// one line per title rated 9+ on the maintainer's MAL — allusions, never quotes
const THINKING_FLAIR_WATCHLIST = ['Weighing the oddity…', 'Checking if it\u2019s a fake…', 'Letting the cat out…', 'Taking the long way home…', 'Checking the mirror…', 'Solving the classroom riddle…', 'Finishing the story…', 'Bleeding a little iron…', 'Reclaiming what was taken…', 'Collecting twelve blades…', 'Reading the fine print on the wish…', 'Tuning the phone microwave…', 'Lingering in déjà vu…', 'Making a move on the board…', 'Playing the final move…', 'Walking the tiger home…', 'Suspecting a conspiracy…', 'Holding the line, Sergeant…', 'Guarding the classroom…', 'Granting a five-yen wish…', 'Settling an old debt…', 'Swapping places for a second…', 'Running the cargo…', 'Reloading both pistols…', 'Following the blood trail…', 'Polishing the gems…', 'Betting it all on a game…', 'Practicing the broom flight…', 'Serving tea, dragon-style…', 'Casting Explosion, once a day…', 'Paying off the party\u2019s debt…', 'Filing a complaint with Being X…', 'Crossing through the gate…', 'Starting over from the save point…', 'Keeping a secret hobby…', 'Finishing the life consultation…', 'Transforming, reluctantly…', 'Rebooting the undead…', 'Tracing the blade…', 'Diving into the net…'];
const ALL_FLAIR = THINKING_FLAIR.concat(THINKING_FLAIR_WATCHLIST);
const STATES = {
  idle: {
    label: 'Idle',
    color: 'var(--text-tertiary)',
    pulse: false
  },
  thinking: {
    label: 'Thinking…',
    color: 'var(--accent)',
    pulse: true
  },
  tool: {
    label: 'Running tool',
    color: 'var(--accent)',
    pulse: true
  },
  approval: {
    label: 'Waiting for your approval',
    color: 'var(--status-warn)',
    pulse: false
  },
  stopped: {
    label: 'Gateway stopped',
    color: 'var(--status-danger)',
    pulse: false
  },
  offline: {
    label: 'Can\u2019t reach Hermes',
    color: 'var(--status-danger)',
    pulse: false
  }
};
function StatusLine({
  state = 'idle',
  label,
  flair = true,
  style
}) {
  const s = STATES[state] || STATES.idle;
  const cycling = state === 'thinking' && !label && flair;
  const [i, setI] = useState(0);
  const [show, setShow] = useState(true);
  useEffect(() => {
    if (!cycling) return;
    setI(0);
    setShow(true);
    const t = setInterval(() => {
      setShow(false);
      setTimeout(() => {
        setI(n => (n + 1) % ALL_FLAIR.length);
        setShow(true);
      }, 180);
    }, 2600);
    return () => clearInterval(t);
  }, [cycling]);
  const text = label || (cycling ? ALL_FLAIR[i] : s.label);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      font: 'var(--type-status)',
      color: 'var(--text-secondary)',
      padding: '6px var(--gutter-page)',
      ...style
    }
  }, /*#__PURE__*/React.createElement("style", null, '@keyframes tsk-pulse{0%,100%{opacity:1}50%{opacity:.35}}'), /*#__PURE__*/React.createElement("span", {
    style: {
      width: 7,
      height: 7,
      borderRadius: 99,
      background: s.color,
      animation: s.pulse ? 'tsk-pulse 2s infinite' : 'none',
      flexShrink: 0
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      opacity: show ? 1 : 0,
      transform: show ? 'none' : 'translateY(2px)',
      transition: 'opacity 180ms var(--ease-quiet), transform 180ms var(--ease-quiet)'
    }
  }, text));
}
Object.assign(__ds_scope, { THINKING_FLAIR, THINKING_FLAIR_WATCHLIST, StatusLine });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/StatusLine.jsx", error: String((e && e.message) || e) }); }

// components/chat/UserBubble.jsx
try { (() => {
function UserBubble({
  children,
  style
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'flex-end',
      padding: '4px var(--gutter-page)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: '82%',
      background: 'var(--bubble-user)',
      color: 'var(--bubble-user-ink)',
      font: 'var(--type-body)',
      borderRadius: 'var(--radius-card)',
      borderBottomRightRadius: 6,
      padding: '10px 14px',
      ...style
    }
  }, children));
}
Object.assign(__ds_scope, { UserBubble });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/UserBubble.jsx", error: String((e && e.message) || e) }); }

// components/chrome/Drawer.jsx
try { (() => {
function Drawer({
  header,
  children,
  footer,
  width = 300,
  style
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      width,
      height: '100%',
      boxSizing: 'border-box',
      background: 'var(--surface-raised)',
      borderRight: '1px solid var(--border-hairline)',
      display: 'flex',
      flexDirection: 'column',
      ...style
    }
  }, header && /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px 20px 10px'
    }
  }, header), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '0 8px'
    }
  }, children), footer && /*#__PURE__*/React.createElement("div", {
    style: {
      borderTop: '1px solid var(--border-hairline)',
      padding: 8
    }
  }, footer));
}
function DrawerLabel({
  children
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-label)',
      letterSpacing: '.08em',
      textTransform: 'uppercase',
      color: 'var(--text-tertiary)',
      padding: '16px 12px 6px'
    }
  }, children);
}
Object.assign(__ds_scope, { Drawer, DrawerLabel });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chrome/Drawer.jsx", error: String((e && e.message) || e) }); }

// components/core/Card.jsx
try { (() => {
function Card({
  floating = false,
  padding = 16,
  children,
  style
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      background: 'var(--surface-raised)',
      borderRadius: 'var(--radius-card)',
      border: '1px solid var(--border-hairline)',
      boxShadow: floating ? 'var(--shadow-float)' : 'none',
      padding,
      boxSizing: 'border-box',
      ...style
    }
  }, children);
}
Object.assign(__ds_scope, { Card });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Card.jsx", error: String((e && e.message) || e) }); }

// components/core/Icon.jsx
try { (() => {
function Icon({
  name,
  size = 24,
  color,
  filled = false,
  style
}) {
  return /*#__PURE__*/React.createElement("span", {
    className: "ms",
    "aria-hidden": "true",
    style: {
      fontSize: size,
      color,
      fontVariationSettings: `'opsz' ${size >= 24 ? 24 : 20},'wght' 300,'FILL' ${filled ? 1 : 0},'GRAD' 0`,
      ...style
    }
  }, name);
}
Object.assign(__ds_scope, { Icon });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Icon.jsx", error: String((e && e.message) || e) }); }

// components/chat/CodeBlock.jsx
try { (() => {
const {
  useState
} = React;
function CodeBlock({
  code,
  lang,
  style
}) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    try {
      navigator.clipboard.writeText(code);
    } catch (e) {}
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative',
      background: 'var(--code-bg)',
      borderRadius: 'var(--radius-code)',
      margin: '8px 0',
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '6px 6px 0 14px'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-label)',
      letterSpacing: '.08em',
      textTransform: 'uppercase',
      color: 'rgba(216,211,232,.55)'
    }
  }, lang || 'code'), /*#__PURE__*/React.createElement("button", {
    onClick: copy,
    "aria-label": "Copy code",
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: 5,
      background: 'none',
      border: 'none',
      cursor: 'pointer',
      color: 'rgba(216,211,232,.75)',
      font: 'var(--type-caption)',
      padding: '6px 8px'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: copied ? 'check' : 'content_copy',
    size: 16
  }), copied ? 'Copied' : 'Copy')), /*#__PURE__*/React.createElement("pre", {
    style: {
      margin: 0,
      padding: '4px 14px 12px',
      font: 'var(--type-code)',
      color: 'var(--code-ink)',
      overflowX: 'auto'
    }
  }, code));
}
Object.assign(__ds_scope, { CodeBlock });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/CodeBlock.jsx", error: String((e && e.message) || e) }); }

// components/chat/ToolRow.jsx
try { (() => {
const {
  useState
} = React;
function ToolRow({
  kind = 'tool',
  name,
  detail,
  status = 'done',
  defaultOpen = false,
  children,
  style
}) {
  const [open, setOpen] = useState(defaultOpen);
  const icon = kind === 'thinking' ? 'psychology' : 'terminal';
  const statusIcon = status === 'running' ? null : status === 'error' ? 'error' : 'check';
  return /*#__PURE__*/React.createElement("div", {
    style: {
      margin: '6px var(--gutter-page)',
      border: '1px solid var(--border-hairline)',
      borderRadius: 'var(--radius-row)',
      background: 'var(--surface-raised)',
      overflow: 'hidden',
      ...style
    }
  }, /*#__PURE__*/React.createElement("button", {
    onClick: () => setOpen(!open),
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      width: '100%',
      minHeight: 44,
      padding: '8px 12px',
      background: 'none',
      border: 'none',
      cursor: 'pointer',
      font: 'var(--type-ui)',
      color: 'var(--text-secondary)',
      textAlign: 'left',
      boxSizing: 'border-box'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 18,
    color: status === 'running' ? 'var(--accent)' : 'var(--text-tertiary)'
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1,
      minWidth: 0,
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    }
  }, name, detail && /*#__PURE__*/React.createElement("span", {
    style: {
      color: 'var(--text-tertiary)'
    }
  }, " \xB7 ", detail)), statusIcon && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: statusIcon,
    size: 16,
    color: status === 'error' ? 'var(--status-danger)' : 'var(--text-tertiary)'
  }), /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "expand_more",
    size: 18,
    style: {
      transform: open ? 'rotate(180deg)' : 'none',
      transition: 'transform var(--dur-base) var(--ease-quiet)'
    }
  })), open && /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '0 12px 12px',
      font: 'var(--type-caption)',
      color: 'var(--text-secondary)'
    }
  }, children));
}
Object.assign(__ds_scope, { ToolRow });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/ToolRow.jsx", error: String((e && e.message) || e) }); }

// components/chrome/ConversationItem.jsx
try { (() => {
const {
  useState,
  useRef
} = React;
function ConversationItem({
  title,
  meta,
  icon,
  selected,
  onClick,
  onLongPress,
  style
}) {
  const [press, setPress] = useState(false);
  const timer = useRef(null);
  const down = () => {
    setPress(true);
    if (onLongPress) timer.current = setTimeout(() => {
      timer.current = null;
      onLongPress();
    }, 480);
  };
  const up = fire => {
    setPress(false);
    if (onLongPress) {
      if (timer.current) {
        clearTimeout(timer.current);
        timer.current = null;
        if (fire && onClick) onClick();
      }
    } else if (fire && onClick) onClick();
  };
  return /*#__PURE__*/React.createElement("button", {
    onPointerDown: down,
    onPointerUp: () => up(true),
    onPointerLeave: () => up(false),
    onContextMenu: e => {
      if (onLongPress) {
        e.preventDefault();
        onLongPress();
      }
    },
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      width: '100%',
      minHeight: 44,
      padding: '10px 12px',
      boxSizing: 'border-box',
      border: 'none',
      borderRadius: 'var(--radius-row)',
      cursor: 'pointer',
      textAlign: 'left',
      font: 'var(--type-ui)',
      transition: 'background var(--dur-quick) var(--ease-quiet)',
      WebkitTapHighlightColor: 'transparent',
      userSelect: 'none',
      background: selected ? 'var(--accent-selected)' : press ? 'var(--wash-press)' : 'transparent',
      color: selected ? 'var(--ink-1)' : 'var(--text-body)',
      ...style
    }
  }, icon && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 20,
    color: "var(--text-secondary)"
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1,
      minWidth: 0,
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    }
  }, title), meta && /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      flexShrink: 0
    }
  }, meta));
}
Object.assign(__ds_scope, { ConversationItem });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chrome/ConversationItem.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
const {
  useState
} = React;
function Button({
  variant = 'secondary',
  size = 'md',
  icon,
  disabled,
  danger,
  children,
  onClick,
  style
}) {
  const [press, setPress] = useState(false);
  const pad = size === 'sm' ? '8px 16px' : '12px 22px';
  const font = size === 'sm' ? '600 13px/1 var(--font-sans)' : '600 14.5px/1 var(--font-sans)';
  const base = {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    padding: pad,
    minHeight: size === 'sm' ? 36 : 44,
    boxSizing: 'border-box',
    borderRadius: 'var(--radius-pill)',
    font,
    cursor: disabled ? 'default' : 'pointer',
    border: '1px solid transparent',
    transition: 'background var(--dur-quick) var(--ease-quiet)',
    opacity: disabled ? 0.45 : 1,
    userSelect: 'none',
    WebkitTapHighlightColor: 'transparent'
  };
  const variants = {
    primary: {
      background: press ? 'var(--accent-pressed)' : 'var(--accent)',
      color: 'var(--accent-on)'
    },
    secondary: {
      background: press ? 'var(--wash-press)' : 'var(--surface-raised)',
      color: danger ? 'var(--status-danger)' : 'var(--text-body)',
      borderColor: 'var(--border-hairline)'
    },
    ghost: {
      background: press ? 'var(--wash-press)' : 'transparent',
      color: danger ? 'var(--status-danger)' : 'var(--text-secondary)'
    }
  };
  return /*#__PURE__*/React.createElement("button", {
    disabled: disabled,
    onClick: onClick,
    style: {
      ...base,
      ...variants[variant],
      ...style
    },
    onPointerDown: () => setPress(true),
    onPointerUp: () => setPress(false),
    onPointerLeave: () => setPress(false)
  }, icon && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: size === 'sm' ? 18 : 20
  }), children);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/chat/ApprovalCard.jsx
try { (() => {
function ApprovalCard({
  title = 'Hermes wants to run a tool',
  toolName,
  command,
  onApprove,
  onDeny,
  style
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      margin: '8px var(--gutter-page)',
      background: 'var(--surface-raised)',
      border: '1px solid var(--border-hairline)',
      borderRadius: 'var(--radius-card)',
      boxShadow: 'var(--shadow-float)',
      padding: 16,
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      marginBottom: 10
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "gpp_maybe",
    size: 20,
    color: "var(--status-warn)"
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-status)',
      color: 'var(--text-body)'
    }
  }, title)), toolName && /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-label)',
      letterSpacing: '.08em',
      textTransform: 'uppercase',
      color: 'var(--text-tertiary)',
      marginBottom: 6
    }
  }, toolName), command && /*#__PURE__*/React.createElement("pre", {
    style: {
      margin: '0 0 14px',
      background: 'var(--code-bg)',
      color: 'var(--code-ink)',
      font: 'var(--type-code)',
      borderRadius: 'var(--radius-code)',
      padding: '10px 12px',
      overflowX: 'auto'
    }
  }, command), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 10,
      justifyContent: 'flex-end'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Button, {
    danger: true,
    onClick: onDeny
  }, "Deny"), /*#__PURE__*/React.createElement(__ds_scope.Button, {
    variant: "primary",
    icon: "check",
    onClick: onApprove
  }, "Approve")));
}
Object.assign(__ds_scope, { ApprovalCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/ApprovalCard.jsx", error: String((e && e.message) || e) }); }

// components/chat/PairingCard.jsx
try { (() => {
function PairingCard({
  platform = 'Telegram',
  who,
  code,
  onApprove,
  onRevoke,
  style
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      margin: '8px var(--gutter-page)',
      background: 'var(--surface-raised)',
      border: '1px solid var(--border-hairline)',
      borderRadius: 'var(--radius-card)',
      boxShadow: 'var(--shadow-float)',
      padding: 16,
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      marginBottom: 10
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "link",
    size: 20,
    color: "var(--status-warn)"
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-status)',
      color: 'var(--text-body)'
    }
  }, "Pairing request from ", platform)), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'baseline',
      justifyContent: 'space-between',
      gap: 12,
      marginBottom: 14
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-ui)',
      color: 'var(--text-secondary)',
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    }
  }, who), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-code)',
      fontSize: 20,
      letterSpacing: '.18em',
      color: 'var(--text-body)'
    }
  }, code)), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 10,
      justifyContent: 'flex-end'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Button, {
    danger: true,
    onClick: onRevoke
  }, "Revoke"), /*#__PURE__*/React.createElement(__ds_scope.Button, {
    variant: "primary",
    icon: "check",
    onClick: onApprove
  }, "Approve")));
}
Object.assign(__ds_scope, { PairingCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/PairingCard.jsx", error: String((e && e.message) || e) }); }

// components/core/Chip.jsx
try { (() => {
const {
  useState
} = React;
function Chip({
  label,
  icon,
  trailingIcon,
  selected,
  onClick,
  style
}) {
  const [press, setPress] = useState(false);
  return /*#__PURE__*/React.createElement("button", {
    onClick: onClick,
    onPointerDown: () => setPress(true),
    onPointerUp: () => setPress(false),
    onPointerLeave: () => setPress(false),
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: 6,
      padding: '7px 12px',
      minHeight: 36,
      borderRadius: 'var(--radius-pill)',
      font: 'var(--type-ui)',
      cursor: 'pointer',
      WebkitTapHighlightColor: 'transparent',
      transition: 'background var(--dur-quick) var(--ease-quiet)',
      background: selected ? 'var(--accent-selected)' : press ? 'var(--wash-press)' : 'transparent',
      color: selected ? 'var(--ink-1)' : 'var(--text-secondary)',
      border: '1px solid ' + (selected ? 'transparent' : 'var(--border-hairline)'),
      ...style
    }
  }, icon && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 18
  }), label, trailingIcon && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: trailingIcon,
    size: 18
  }));
}
Object.assign(__ds_scope, { Chip });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Chip.jsx", error: String((e && e.message) || e) }); }

// components/core/Dialog.jsx
try { (() => {
function Dialog({
  open,
  title,
  body,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger,
  onConfirm,
  onCancel
}) {
  if (!open) return null;
  return /*#__PURE__*/React.createElement("div", {
    onClick: onCancel,
    style: {
      position: 'absolute',
      inset: 0,
      zIndex: 40,
      background: 'rgba(39,34,32,.32)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: 28
    }
  }, /*#__PURE__*/React.createElement("div", {
    onClick: e => e.stopPropagation(),
    role: "alertdialog",
    style: {
      width: '100%',
      maxWidth: 340,
      background: 'var(--surface-raised)',
      borderRadius: 'var(--radius-card)',
      padding: 22,
      boxShadow: 'var(--shadow-float)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-title)',
      fontSize: 19,
      color: 'var(--text-body)',
      marginBottom: 8
    }
  }, title), body && /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-body)',
      fontSize: 14.5,
      color: 'var(--text-secondary)',
      marginBottom: 20
    }
  }, body), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'flex-end',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Button, {
    variant: "ghost",
    onClick: onCancel
  }, cancelLabel), /*#__PURE__*/React.createElement(__ds_scope.Button, {
    variant: danger ? 'secondary' : 'primary',
    danger: danger,
    onClick: onConfirm
  }, confirmLabel))));
}
Object.assign(__ds_scope, { Dialog });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Dialog.jsx", error: String((e && e.message) || e) }); }

// components/core/IconButton.jsx
try { (() => {
const {
  useState
} = React;
function IconButton({
  icon,
  label,
  accent = false,
  size = 44,
  onClick,
  style
}) {
  const [press, setPress] = useState(false);
  return /*#__PURE__*/React.createElement("button", {
    "aria-label": label,
    onClick: onClick,
    onPointerDown: () => setPress(true),
    onPointerUp: () => setPress(false),
    onPointerLeave: () => setPress(false),
    style: {
      width: size,
      height: size,
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      border: 'none',
      borderRadius: 'var(--radius-pill)',
      cursor: 'pointer',
      WebkitTapHighlightColor: 'transparent',
      transition: 'background var(--dur-quick) var(--ease-quiet)',
      background: accent ? press ? 'var(--accent-pressed)' : 'var(--accent)' : press ? 'var(--wash-press)' : 'transparent',
      color: accent ? 'var(--accent-on)' : 'var(--text-secondary)',
      ...style
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: size < 44 ? 20 : 24
  }));
}
Object.assign(__ds_scope, { IconButton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/IconButton.jsx", error: String((e && e.message) || e) }); }

// components/chat/Composer.jsx
try { (() => {
const {
  useState
} = React;
function Composer({
  value,
  onChange,
  onSend,
  onAttach,
  placeholder = 'Message your agent',
  disabled = false,
  style
}) {
  const [inner, setInner] = useState('');
  const v = value !== undefined ? value : inner;
  const set = onChange || setInner;
  const send = () => {
    if (disabled) return;
    if (v.trim() && onSend) onSend(v);
    if (value === undefined) setInner('');
  };
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'flex-end',
      gap: 4,
      background: 'var(--surface-raised)',
      border: '1px solid var(--border-hairline)',
      borderRadius: 'var(--radius-composer)',
      boxShadow: 'var(--shadow-float)',
      padding: 6,
      boxSizing: 'border-box',
      opacity: disabled ? .55 : 1,
      ...style
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.IconButton, {
    icon: "add",
    label: "Attach",
    onClick: disabled ? undefined : onAttach
  }), /*#__PURE__*/React.createElement("textarea", {
    rows: 1,
    value: v,
    disabled: disabled,
    placeholder: disabled ? 'Hermes is unreachable' : placeholder,
    onChange: e => set(e.target.value),
    onKeyDown: e => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        send();
      }
    },
    style: {
      flex: 1,
      resize: 'none',
      border: 'none',
      outline: 'none',
      background: 'transparent',
      font: 'var(--type-body)',
      color: 'var(--text-body)',
      padding: '11px 4px',
      maxHeight: 120
    }
  }), /*#__PURE__*/React.createElement(__ds_scope.IconButton, {
    icon: "arrow_upward",
    label: "Send",
    accent: !disabled,
    onClick: send
  }));
}
Object.assign(__ds_scope, { Composer });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chat/Composer.jsx", error: String((e && e.message) || e) }); }

// components/chrome/TopBar.jsx
try { (() => {
function TopBar({
  model = 'Hermes 4',
  showMenu = true,
  onMenu,
  onModel,
  onOverflow,
  style
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 4,
      padding: '8px 8px 2px',
      background: 'var(--surface-page)',
      ...style
    }
  }, showMenu && /*#__PURE__*/React.createElement(__ds_scope.IconButton, {
    icon: "menu",
    label: "Conversations",
    onClick: onMenu
  }), /*#__PURE__*/React.createElement(__ds_scope.Chip, {
    label: model,
    trailingIcon: "expand_more",
    onClick: onModel,
    style: {
      border: 'none'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }), /*#__PURE__*/React.createElement(__ds_scope.IconButton, {
    icon: "more_vert",
    label: "More",
    onClick: onOverflow
  }));
}
Object.assign(__ds_scope, { TopBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/chrome/TopBar.jsx", error: String((e && e.message) || e) }); }

// components/core/Row.jsx
try { (() => {
const {
  useState
} = React;
function Row({
  icon,
  title,
  subtitle,
  meta,
  trailing,
  trailingIcon,
  onClick,
  onLongPress,
  selected,
  danger,
  style
}) {
  const [press, setPress] = useState(false);
  const timer = React.useRef(null);
  const down = () => {
    setPress(true);
    if (onLongPress) timer.current = setTimeout(() => {
      timer.current = null;
      onLongPress();
    }, 480);
  };
  const up = fire => {
    setPress(false);
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
      if (fire && onClick) onClick();
    } else if (!onLongPress && fire && onClick) onClick();
  };
  const Tag = onClick || onLongPress ? 'button' : 'div';
  return /*#__PURE__*/React.createElement(Tag, {
    onPointerDown: down,
    onPointerUp: () => up(true),
    onPointerLeave: () => up(false),
    onContextMenu: e => {
      if (onLongPress) {
        e.preventDefault();
        onLongPress();
      }
    },
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12,
      width: '100%',
      minHeight: 52,
      padding: '10px 12px',
      boxSizing: 'border-box',
      border: 'none',
      borderRadius: 'var(--radius-row)',
      cursor: onClick || onLongPress ? 'pointer' : 'default',
      textAlign: 'left',
      font: 'var(--type-ui)',
      fontWeight: 500,
      transition: 'background var(--dur-quick) var(--ease-quiet)',
      WebkitTapHighlightColor: 'transparent',
      userSelect: 'none',
      background: selected ? 'var(--accent-selected)' : press ? 'var(--wash-press)' : 'transparent',
      color: danger ? 'var(--status-danger)' : 'var(--text-body)',
      ...style
    }
  }, icon && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 22,
    color: danger ? 'var(--status-danger)' : 'var(--text-secondary)'
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1,
      minWidth: 0,
      display: 'flex',
      flexDirection: 'column',
      gap: 2
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    }
  }, title), subtitle && /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    }
  }, subtitle)), meta && /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      flexShrink: 0
    }
  }, meta), trailing, trailingIcon && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: trailingIcon,
    size: 20,
    color: "var(--text-tertiary)"
  }));
}
Object.assign(__ds_scope, { Row });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Row.jsx", error: String((e && e.message) || e) }); }

// components/core/Menu.jsx
try { (() => {
function Menu({
  open,
  title,
  items = [],
  onClose
}) {
  if (!open) return null;
  return /*#__PURE__*/React.createElement("div", {
    onClick: onClose,
    style: {
      position: 'absolute',
      inset: 0,
      zIndex: 40,
      background: 'rgba(39,34,32,.24)',
      display: 'flex',
      alignItems: 'flex-end'
    }
  }, /*#__PURE__*/React.createElement("div", {
    onClick: e => e.stopPropagation(),
    role: "menu",
    style: {
      width: '100%',
      background: 'var(--surface-raised)',
      borderRadius: '20px 20px 0 0',
      padding: '10px 8px 18px',
      boxShadow: 'var(--shadow-float)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 32,
      height: 4,
      borderRadius: 99,
      background: 'var(--border-hairline)',
      margin: '0 auto 10px'
    }
  }), title && /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-label)',
      letterSpacing: '.08em',
      textTransform: 'uppercase',
      color: 'var(--text-tertiary)',
      padding: '4px 12px 8px',
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    }
  }, title), items.map((it, i) => /*#__PURE__*/React.createElement(__ds_scope.Row, {
    key: i,
    icon: it.icon,
    title: it.label,
    danger: it.danger,
    onClick: () => {
      onClose && onClose();
      it.onClick && it.onClick();
    }
  }))));
}
Object.assign(__ds_scope, { Menu });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Menu.jsx", error: String((e && e.message) || e) }); }

// components/core/Skeleton.jsx
try { (() => {
function Skeleton({
  width = '100%',
  height = 14,
  radius = 6,
  style
}) {
  return /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      width,
      height,
      borderRadius: radius,
      background: 'var(--surface-sunken)',
      animation: 'tsk-pulse 1.6s infinite',
      ...style
    }
  }, /*#__PURE__*/React.createElement("style", null, '@keyframes tsk-pulse{0%,100%{opacity:1}50%{opacity:.45}}'));
}
Object.assign(__ds_scope, { Skeleton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Skeleton.jsx", error: String((e && e.message) || e) }); }

// components/core/Switch.jsx
try { (() => {
function Switch({
  checked,
  onChange,
  label,
  style
}) {
  return /*#__PURE__*/React.createElement("label", {
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: 12,
      cursor: 'pointer',
      font: 'var(--type-ui)',
      color: 'var(--text-body)',
      minHeight: 44,
      ...style
    }
  }, label, /*#__PURE__*/React.createElement("span", {
    role: "switch",
    "aria-checked": !!checked,
    onClick: () => onChange && onChange(!checked),
    style: {
      width: 44,
      height: 26,
      borderRadius: 99,
      padding: 3,
      boxSizing: 'border-box',
      background: checked ? 'var(--accent)' : 'var(--surface-sunken)',
      border: '1px solid ' + (checked ? 'var(--accent)' : 'var(--border-hairline)'),
      transition: 'background var(--dur-base) var(--ease-quiet)',
      display: 'inline-flex',
      justifyContent: checked ? 'flex-end' : 'flex-start'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 18,
      height: 18,
      borderRadius: 99,
      background: checked ? 'var(--accent-on)' : 'var(--text-tertiary)',
      transition: 'all var(--dur-base) var(--ease-quiet)'
    }
  })));
}
Object.assign(__ds_scope, { Switch });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Switch.jsx", error: String((e && e.message) || e) }); }

// components/core/TextField.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const {
  useState
} = React;
function TextField({
  label,
  value,
  onChange,
  placeholder,
  helper,
  type = 'text',
  mono = false,
  multiline = false,
  rows = 4,
  style
}) {
  const [focus, setFocus] = useState(false);
  const base = {
    font: mono ? 'var(--type-code)' : 'var(--type-body)',
    color: 'var(--text-body)',
    background: 'var(--surface-raised)',
    border: '1px solid var(--border-hairline)',
    borderRadius: 'var(--radius-input)',
    padding: '12px 14px',
    outline: 'none',
    minHeight: 44,
    boxSizing: 'border-box',
    boxShadow: focus ? 'var(--ring-focus)' : 'none',
    transition: 'box-shadow var(--dur-quick) var(--ease-quiet)',
    width: '100%',
    resize: 'vertical'
  };
  const handlers = {
    onChange: e => onChange && onChange(e.target.value),
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false)
  };
  return /*#__PURE__*/React.createElement("label", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 6,
      ...style
    }
  }, label && /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-label)',
      letterSpacing: '.08em',
      textTransform: 'uppercase',
      color: 'var(--text-tertiary)'
    }
  }, label), multiline ? /*#__PURE__*/React.createElement("textarea", _extends({
    rows: rows,
    value: value,
    placeholder: placeholder
  }, handlers, {
    style: base
  })) : /*#__PURE__*/React.createElement("input", _extends({
    type: type,
    value: value,
    placeholder: placeholder
  }, handlers, {
    style: base
  })), helper && /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)'
    }
  }, helper));
}
Object.assign(__ds_scope, { TextField });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/TextField.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/app.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const seed = [{
  role: 'user',
  text: 'The nightly backup cron is failing. Can you take a look?'
}, {
  role: 'thinking',
  text: 'Thought for 9s',
  body: 'Check the cron log first, then the service token.'
}, {
  role: 'tool',
  name: 'shell',
  detail: 'tail -n 50 /var/log/cron.log',
  status: 'done',
  output: 'Oct 19 03:00:01 CRON[8812]: backup.sh exited 401\nOct 19 03:00:01 CRON[8812]: token rejected'
}, {
  role: 'assistant',
  text: 'The backup script is getting a 401 — the storage token expired on the 16th. I can rotate it and restart the agent, but the restart needs your sign-off:'
}, {
  role: 'approval',
  name: 'shell',
  command: 'systemctl restart hermes-agent'
}];
const sessionSeed = [{
  id: 1,
  profile: 'default',
  title: 'Fix the cron job',
  meta: '2h',
  sid: 'ses_8f2a19'
}, {
  id: 2,
  profile: 'default',
  title: 'Summarize server logs',
  meta: '1d',
  sid: 'ses_c71e04'
}, {
  id: 3,
  profile: 'ops',
  title: 'Backup rotation plan',
  meta: '3d',
  sid: 'ses_2b9d77'
}, {
  id: 4,
  profile: 'research',
  title: 'Paper triage',
  meta: '4d',
  sid: 'ses_e40a12'
}, {
  id: 5,
  profile: 'research',
  title: 'Dataset licence check',
  meta: '1w',
  sid: 'ses_91f0c3'
}, {
  id: 6,
  profile: 'ops',
  title: 'Old migration notes',
  meta: '2mo',
  sid: 'ses_0a1b2c',
  archived: true
}];
const pairingSeed = {
  platform: 'Telegram',
  who: '@kana · 184 229 310',
  code: '4F2-91K'
};
// ≥720dp (unfolded Z Fold, tablet, desktop web) → persistent two-pane; below → phone with drawer overlay
function useWide(ref) {
  const [wide, setWide] = React.useState(false);
  React.useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setWide(e.contentRect.width >= 720));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return wide;
}
function App({
  start = 'onboarding',
  forceTheme
}) {
  const ref = React.useRef(null);
  const wide = useWide(ref);
  const [screen, setScreen] = React.useState(start);
  const [editJob, setEditJob] = React.useState(undefined); // undefined = closed, null = new, obj = edit
  const [drawer, setDrawer] = React.useState(false);
  const [sessions, setSessions] = React.useState(sessionSeed);
  const [active, setActive] = React.useState(1);
  const [messages, setMessages] = React.useState(seed);
  const [status, setStatus] = React.useState('approval');
  const [statusLabel, setStatusLabel] = React.useState(null);
  const [gateway, setGateway] = React.useState('running');
  const [pairing, setPairing] = React.useState(pairingSeed);
  const [jobs, setJobs] = React.useState(cronSeed);
  const [profile, setProfile] = React.useState('default');
  const [workspace, setWorkspace] = React.useState('~/projects/hermes');
  const [model, setModel] = React.useState(MODELS[0]);
  const [theme, setTheme] = React.useState(forceTheme || 'System');
  const [offline, setOffline] = React.useState(false);
  const [loading, setLoading] = React.useState(false);
  const [sysDark, setSysDark] = React.useState(() => window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  React.useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const h = e => setSysDark(e.matches);
    mq.addEventListener('change', h);
    return () => mq.removeEventListener('change', h);
  }, []);
  const dark = theme === 'Dark' || theme === 'System' && sysDark;
  const timers = React.useRef([]);
  React.useEffect(() => () => timers.current.forEach(clearTimeout), []);
  const later = (ms, fn) => timers.current.push(setTimeout(fn, ms));
  const screenRef = React.useRef(screen);
  screenRef.current = screen;
  // ?demo=1 — autoplay for embeds
  const demo = React.useMemo(() => new URLSearchParams(location.search).get('demo') === '1', []);
  const [fade, setFade] = React.useState(false);
  React.useEffect(() => {
    if (!demo) return;
    const steps = [['onboarding', false, 3200], ['status', false, 3800], ['chat', false, 4200], ['chat', true, 3000], ['cron', false, 3200], ['settings', false, 3400]];
    let i = 0,
      t;
    const go = () => {
      const [s, d, ms] = steps[i];
      const switching = s !== screenRef.current;
      if (switching) setFade(true);
      setTimeout(() => {
        setScreen(s);
        setDrawer(d);
        setFade(false);
      }, switching ? 220 : 0);
      i = (i + 1) % steps.length;
      t = setTimeout(go, ms);
    };
    go();
    return () => clearTimeout(t);
  }, [demo]);
  const send = text => {
    if (offline) return;
    setMessages(m => [...m, {
      role: 'user',
      text
    }]);
    setStatus('thinking');
    setStatusLabel(null);
    later(1400, () => {
      setStatus('tool');
      setStatusLabel('Running shell command');
    });
    later(2600, () => {
      setMessages(m => [...m, {
        role: 'tool',
        name: 'shell',
        detail: 'uptime',
        status: 'done',
        output: ' 14:02:11 up 41 days,  load average: 0.12'
      }, {
        role: 'assistant',
        text: '',
        streaming: true
      }]);
      setStatus('thinking');
      setStatusLabel('Replying…');
      const words = 'Done — the box has been up 41 days and load is flat. Everything looks healthy from here.'.split(' ');
      words.forEach((w, i) => later(2700 + i * 70, () => setMessages(m => {
        const c = m.slice();
        const last = {
          ...c[c.length - 1]
        };
        last.text = words.slice(0, i + 1).join(' ');
        last.streaming = i < words.length - 1;
        c[c.length - 1] = last;
        return c;
      })));
      later(2700 + words.length * 70 + 100, () => {
        setStatus('idle');
        setStatusLabel(null);
      });
    });
  };
  const resolveApproval = approved => {
    setMessages(m => m.filter(x => x.role !== 'approval').concat(approved ? [{
      role: 'tool',
      name: 'shell',
      detail: 'systemctl restart hermes-agent',
      status: 'done',
      output: 'hermes-agent.service: restarted (pid 4182)'
    }, {
      role: 'assistant',
      text: 'Restarted with the new token. The 3am run should succeed — I\u2019ll flag it if not.'
    }] : [{
      role: 'assistant',
      text: 'Understood — I left the agent running. The backup will keep failing until the token is rotated.'
    }]));
    setStatus('idle');
    setStatusLabel(null);
  };
  const resolvePairing = ok => {
    setPairing(null);
    setMessages(m => m.filter(x => x.role !== 'pairing').concat([{
      role: 'assistant',
      text: ok ? 'Paired @kana on Telegram.' : 'Revoked the pairing request from @kana.'
    }]));
  };
  const onGateway = action => {
    if (action === 'stop') {
      setGateway('stopped');
      setStatus('stopped');
      return;
    }
    setGateway('restarting');
    setStatusLabel(null);
    setStatus('tool');
    setStatusLabel(action === 'start' ? 'Starting gateway' : 'Restarting gateway');
    later(1600, () => {
      setGateway('running');
      setStatus('idle');
      setStatusLabel(null);
    });
  };
  const newChat = () => {
    setActive(null);
    setMessages([]);
    setStatus(gateway === 'running' ? 'idle' : 'stopped');
    setStatusLabel(null);
    setDrawer(false);
    setScreen('chat');
  };
  const pick = id => {
    setActive(id);
    setDrawer(false);
    setScreen('chat');
    setLoading(true);
    later(650, () => {
      setLoading(false);
      setMessages(id === 1 ? seed : [{
        role: 'assistant',
        text: 'Picking up where we left off.'
      }]);
      setStatus(id === 1 ? 'approval' : 'idle');
    });
  };
  const session = sessions.find(s => s.id === active);
  const sessionActions = {
    onRename: t => setSessions(ss => ss.map(x => x.id === active ? {
      ...x,
      title: t
    } : x)),
    onArchive: () => {
      setSessions(ss => ss.map(x => x.id === active ? {
        ...x,
        archived: true
      } : x));
      setScreen('status');
    },
    onDelete: () => {
      setSessions(ss => ss.filter(x => x.id !== active));
      setActive(null);
      setMessages([]);
      setScreen('status');
    }
  };
  const go = s => () => {
    setDrawer(false);
    setScreen(s);
  };
  const drawerProps = {
    sessions,
    setSessions,
    active,
    onPick: pick,
    onNew: newChat,
    onStatus: go('status'),
    onCron: go('cron'),
    onSettings: go('settings')
  };
  const activeTitle = (sessions.find(s => s.id === active) || {}).title;
  const main = /*#__PURE__*/React.createElement(React.Fragment, null, screen === 'onboarding' && /*#__PURE__*/React.createElement(OnboardingScreen, {
    onConnect: () => setScreen('settings')
  }), screen === 'status' && /*#__PURE__*/React.createElement(StatusScreen, {
    showMenu: !wide,
    onMenu: () => setDrawer(true),
    gateway: gateway,
    onGateway: onGateway,
    pairing: pairing,
    onPairing: resolvePairing,
    lastSession: activeTitle,
    onContinue: () => setScreen('chat'),
    onNew: newChat,
    onCron: go('cron'),
    onSettings: go('settings'),
    offline: offline,
    onRetry: () => setOffline(false)
  }), screen === 'chat' && /*#__PURE__*/React.createElement(ChatScreen, _extends({
    showMenu: !wide,
    onMenu: () => setDrawer(true),
    status: status,
    statusLabel: statusLabel,
    loading: loading,
    messages: pairing && active === 1 ? [...messages.slice(0, 1), {
      role: 'pairing',
      ...pairing
    }, ...messages.slice(1)] : messages,
    onSend: send,
    onApprove: () => resolveApproval(true),
    onDeny: () => resolveApproval(false),
    onPairing: resolvePairing,
    workspace: workspace,
    onWorkspace: setWorkspace,
    onStatusTap: go('status'),
    model: model,
    onModel: setModel,
    offline: offline,
    session: session
  }, sessionActions)), screen === 'cron' && editJob === undefined && /*#__PURE__*/React.createElement(CronScreen, {
    onBack: go('status'),
    jobs: jobs,
    setJobs: setJobs,
    onEdit: j => setEditJob(j),
    onNew: () => setEditJob(null)
  }), screen === 'cron' && editJob !== undefined && /*#__PURE__*/React.createElement(CronEditScreen, {
    job: editJob,
    onBack: () => setEditJob(undefined),
    onSave: f => {
      setJobs(js => f.id ? js.map(x => x.id === f.id ? {
        ...x,
        ...f
      } : x) : [...js, {
        ...f,
        id: Date.now(),
        human: 'Custom schedule',
        next: 'in 1d',
        paused: false
      }]);
      setEditJob(undefined);
    }
  }), screen === 'settings' && /*#__PURE__*/React.createElement(SettingsScreen, {
    onBack: go('status'),
    onLogs: go('logs'),
    profile: profile,
    setProfile: setProfile,
    pairing: pairing,
    onPairing: resolvePairing,
    theme: theme,
    setTheme: setTheme,
    offline: offline,
    setOffline: setOffline
  }), screen === 'logs' && /*#__PURE__*/React.createElement(LogsScreen, {
    onBack: go('settings')
  }));
  const showPane = wide && screen !== 'onboarding';
  return /*#__PURE__*/React.createElement("div", {
    ref: ref,
    className: "app",
    "data-theme": dark ? 'dark' : undefined,
    style: {
      flexDirection: 'row',
      colorScheme: dark ? 'dark' : 'light'
    }
  }, showPane && /*#__PURE__*/React.createElement(DrawerOverlay, _extends({
    persistent: true
  }, drawerProps)), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0,
      height: '100%',
      position: 'relative',
      display: 'flex',
      flexDirection: 'column'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minHeight: 0,
      width: '100%',
      maxWidth: wide ? 760 : 'none',
      margin: '0 auto',
      position: 'relative',
      opacity: fade ? 0 : 1,
      transition: 'opacity 220ms var(--ease-quiet)'
    }
  }, main)), !wide && /*#__PURE__*/React.createElement(DrawerOverlay, _extends({
    open: drawer,
    onClose: () => setDrawer(false)
  }, drawerProps)));
}
Object.assign(window, {
  App
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/app.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/chat-screen.jsx
try { (() => {
const MODELS = ['Hermes 4 · 405B', 'Hermes 4 · 70B', 'Hermes 3 · 8B'];
const WORKSPACES = ['~/projects/hermes', '~/notes', '~/sites/blog', '/srv/backups'];
function ChatScreen({
  onMenu,
  showMenu = true,
  status,
  statusLabel,
  messages,
  loading,
  onSend,
  onApprove,
  onDeny,
  onPairing,
  workspace,
  onWorkspace,
  onStatusTap,
  model,
  onModel,
  offline,
  session,
  onRename,
  onArchive,
  onDelete
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const endRef = React.useRef(null);
  const [sheet, setSheet] = React.useState(null); // 'model' | 'ws' | 'more'
  const [ask, setAsk] = React.useState(null); // 'rename' | 'delete'
  const [name, setName] = React.useState('');
  const [toast, setToast] = React.useState(null);
  const say = t => {
    setToast(t);
    setTimeout(() => setToast(null), 1600);
  };
  React.useEffect(() => {
    const el = endRef.current;
    if (el && el.parentNode) el.parentNode.scrollTop = el.parentNode.scrollHeight;
  }, [messages, status]);
  const fresh = messages.length === 0 && !loading;
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement(DS.TopBar, {
    model: model,
    onMenu: onMenu,
    showMenu: showMenu,
    onModel: () => setSheet('model'),
    onOverflow: () => session && setSheet('more')
  }), /*#__PURE__*/React.createElement("div", {
    onClick: onStatusTap,
    style: {
      cursor: onStatusTap ? 'pointer' : 'default'
    }
  }, /*#__PURE__*/React.createElement(DS.StatusLine, {
    state: offline ? 'offline' : status,
    label: offline ? undefined : statusLabel
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '6px 0 10px'
    }
  }, loading && /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '12px 20px',
      display: 'flex',
      flexDirection: 'column',
      gap: 14
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'flex-end'
    }
  }, /*#__PURE__*/React.createElement(DS.Skeleton, {
    width: "56%",
    height: 40,
    radius: 16
  })), /*#__PURE__*/React.createElement(DS.Skeleton, {
    width: "92%"
  }), /*#__PURE__*/React.createElement(DS.Skeleton, {
    width: "78%"
  }), /*#__PURE__*/React.createElement(DS.Skeleton, {
    width: "40%"
  }), /*#__PURE__*/React.createElement(DS.Skeleton, {
    width: "100%",
    height: 44,
    radius: 12
  })), fresh && /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18vh 20px 0',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: 14,
      textAlign: 'center'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-status)',
      fontSize: 16,
      color: 'var(--text-secondary)'
    }
  }, "New chat"), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)'
    }
  }, "Working directory"), /*#__PURE__*/React.createElement(DS.Chip, {
    icon: "folder",
    label: workspace,
    trailingIcon: "expand_more",
    onClick: () => setSheet('ws')
  })), !loading && messages.map((m, i) => {
    if (m.role === 'user') return /*#__PURE__*/React.createElement(DS.UserBubble, {
      key: i
    }, m.text);
    if (m.role === 'tool') return /*#__PURE__*/React.createElement(DS.ToolRow, {
      key: i,
      name: m.name,
      detail: m.detail,
      status: m.status
    }, m.output && /*#__PURE__*/React.createElement(DS.CodeBlock, {
      lang: "log",
      code: m.output
    }));
    if (m.role === 'thinking') return /*#__PURE__*/React.createElement(DS.ToolRow, {
      key: i,
      kind: "thinking",
      name: m.text,
      status: "done"
    }, m.body);
    if (m.role === 'approval') return /*#__PURE__*/React.createElement(DS.ApprovalCard, {
      key: i,
      toolName: m.name,
      command: m.command,
      onApprove: onApprove,
      onDeny: onDeny
    });
    if (m.role === 'pairing') return /*#__PURE__*/React.createElement(DS.PairingCard, {
      key: i,
      platform: m.platform,
      who: m.who,
      code: m.code,
      onApprove: () => onPairing(true),
      onRevoke: () => onPairing(false)
    });
    return /*#__PURE__*/React.createElement(DS.AssistantMessage, {
      key: i,
      streaming: m.streaming
    }, m.text, m.code && /*#__PURE__*/React.createElement(DS.CodeBlock, {
      lang: m.lang,
      code: m.code
    }));
  }), /*#__PURE__*/React.createElement("div", {
    ref: endRef
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '6px 12px 14px'
    }
  }, /*#__PURE__*/React.createElement(DS.Composer, {
    onSend: onSend,
    disabled: offline,
    onAttach: () => say('Attach: files and images upload into ' + workspace)
  })), toast && /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 16,
      right: 16,
      bottom: 84,
      background: 'var(--ink-1)',
      color: 'var(--cream-0)',
      font: 'var(--type-ui)',
      padding: '12px 16px',
      borderRadius: 'var(--radius-row)',
      zIndex: 30
    }
  }, toast), /*#__PURE__*/React.createElement(DS.Menu, {
    open: sheet === 'model',
    title: "Model",
    onClose: () => setSheet(null),
    items: MODELS.map(m => ({
      icon: m === model ? 'check' : 'radio_button_unchecked',
      label: m,
      onClick: () => onModel(m)
    }))
  }), /*#__PURE__*/React.createElement(DS.Menu, {
    open: sheet === 'ws',
    title: "Working directory",
    onClose: () => setSheet(null),
    items: WORKSPACES.map(w => ({
      icon: w === workspace ? 'check' : 'folder',
      label: w,
      onClick: () => onWorkspace(w)
    }))
  }), /*#__PURE__*/React.createElement(DS.Menu, {
    open: sheet === 'more',
    title: session && session.title,
    onClose: () => setSheet(null),
    items: [{
      icon: 'edit',
      label: 'Rename',
      onClick: () => {
        setName(session.title);
        setAsk('rename');
      }
    }, {
      icon: 'archive',
      label: 'Archive',
      onClick: onArchive
    }, {
      icon: 'content_copy',
      label: 'Copy session ID',
      onClick: () => say('Copied ' + (session && session.sid))
    }, {
      icon: 'delete',
      label: 'Delete session',
      danger: true,
      onClick: () => setAsk('delete')
    }]
  }), /*#__PURE__*/React.createElement(DS.Dialog, {
    open: ask === 'rename',
    title: "Rename session",
    confirmLabel: "Save",
    onConfirm: () => {
      onRename(name);
      setAsk(null);
    },
    onCancel: () => setAsk(null),
    body: /*#__PURE__*/React.createElement(DS.TextField, {
      value: name,
      onChange: setName
    })
  }), /*#__PURE__*/React.createElement(DS.Dialog, {
    open: ask === 'delete',
    danger: true,
    title: "Delete session?",
    body: "Messages and tool output are removed from Hermes. This can\u2019t be undone.",
    confirmLabel: "Delete",
    onConfirm: () => {
      onDelete();
      setAsk(null);
    },
    onCancel: () => setAsk(null)
  }));
}
Object.assign(window, {
  ChatScreen,
  MODELS,
  WORKSPACES
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/chat-screen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/cron-screen.jsx
try { (() => {
// /api/cron/jobs — list, pause/resume, trigger, delete; edit is full-screen
const cronSeed = [{
  id: 1,
  name: 'Nightly backup',
  expr: '0 3 * * *',
  human: 'Every day at 03:00',
  next: 'in 9h',
  deliver: 'telegram',
  prompt: 'Run the backup script and report size and duration.',
  paused: false
}, {
  id: 2,
  name: 'Inbox triage',
  expr: '*/30 8-20 * * 1-5',
  human: 'Every 30 min, weekdays 8–20',
  next: 'in 12m',
  deliver: 'none',
  prompt: 'Summarize unread mail; flag anything from clients.',
  paused: false
}, {
  id: 3,
  name: 'Paper digest',
  expr: '0 7 * * 1',
  human: 'Mondays at 07:00',
  next: 'paused',
  deliver: 'discord',
  prompt: 'Pull new arXiv papers matching my topics and write a digest.',
  paused: true
}];
function CronScreen({
  onBack,
  jobs,
  setJobs,
  onEdit,
  onNew
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [menu, setMenu] = React.useState(null);
  const [ask, setAsk] = React.useState(null);
  const [toast, setToast] = React.useState(null);
  const say = t => {
    setToast(t);
    setTimeout(() => setToast(null), 1800);
  };
  const toggle = j => setJobs(js => js.map(x => x.id === j.id ? {
    ...x,
    paused: !x.paused,
    next: x.paused ? 'in 2h' : 'paused'
  } : x));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 4,
      padding: '8px 8px 2px'
    }
  }, /*#__PURE__*/React.createElement(DS.IconButton, {
    icon: "arrow_back",
    label: "Back",
    onClick: onBack
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-title)',
      color: 'var(--text-body)'
    }
  }, "Cron"), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }), /*#__PURE__*/React.createElement(DS.Button, {
    size: "sm",
    icon: "add",
    onClick: onNew
  }, "New job")), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '10px 12px 20px',
      display: 'flex',
      flexDirection: 'column',
      gap: 4
    }
  }, jobs.map(j => /*#__PURE__*/React.createElement(DS.Row, {
    key: j.id,
    icon: j.paused ? 'pause_circle' : 'schedule',
    title: j.name,
    subtitle: j.human + ' · ' + j.expr,
    meta: j.next,
    onClick: () => setMenu(j),
    onLongPress: () => setMenu(j),
    style: {
      opacity: j.paused ? .6 : 1
    }
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      padding: '12px 12px 0'
    }
  }, "Tap a job for actions. Delivery goes to the job's platform, or stays in a session.")), toast && /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 16,
      right: 16,
      bottom: 20,
      background: 'var(--ink-1)',
      color: 'var(--cream-0)',
      font: 'var(--type-ui)',
      padding: '12px 16px',
      borderRadius: 'var(--radius-row)'
    }
  }, toast), /*#__PURE__*/React.createElement(DS.Menu, {
    open: !!menu,
    title: menu && menu.name,
    onClose: () => setMenu(null),
    items: menu ? [{
      icon: 'play_arrow',
      label: 'Run now',
      onClick: () => say('Triggered ' + menu.name)
    }, {
      icon: menu.paused ? 'play_circle' : 'pause_circle',
      label: menu.paused ? 'Resume' : 'Pause',
      onClick: () => toggle(menu)
    }, {
      icon: 'edit',
      label: 'Edit',
      onClick: () => onEdit(menu)
    }, {
      icon: 'delete',
      label: 'Delete',
      danger: true,
      onClick: () => setAsk(menu)
    }] : []
  }), /*#__PURE__*/React.createElement(DS.Dialog, {
    open: !!ask,
    danger: true,
    title: "Delete this job?",
    body: ask && /*#__PURE__*/React.createElement("span", null, "\u201C", ask.name, "\u201D stops running and can\u2019t be restored."),
    confirmLabel: "Delete",
    onConfirm: () => {
      setJobs(js => js.filter(x => x.id !== ask.id));
      setAsk(null);
    },
    onCancel: () => setAsk(null)
  }));
}
function CronEditScreen({
  job,
  onBack,
  onSave
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [f, setF] = React.useState(job || {
    name: '',
    prompt: '',
    expr: '0 9 * * *',
    deliver: 'none'
  });
  const set = k => v => setF(x => ({
    ...x,
    [k]: v
  }));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      display: 'flex',
      flexDirection: 'column'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 4,
      padding: '8px 8px 2px'
    }
  }, /*#__PURE__*/React.createElement(DS.IconButton, {
    icon: "close",
    label: "Cancel",
    onClick: onBack
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-title)',
      color: 'var(--text-body)'
    }
  }, job ? 'Edit job' : 'New job'), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }), /*#__PURE__*/React.createElement(DS.Button, {
    size: "sm",
    variant: "primary",
    onClick: () => onSave(f),
    disabled: !f.name || !f.prompt
  }, "Save")), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '14px 20px 24px',
      display: 'flex',
      flexDirection: 'column',
      gap: 18
    }
  }, /*#__PURE__*/React.createElement(DS.TextField, {
    label: "Name",
    value: f.name,
    onChange: set('name'),
    placeholder: "Nightly backup"
  }), /*#__PURE__*/React.createElement(DS.TextField, {
    label: "Prompt",
    multiline: true,
    rows: 5,
    value: f.prompt,
    onChange: set('prompt'),
    placeholder: "What should the agent do when this fires?"
  }), /*#__PURE__*/React.createElement(DS.TextField, {
    label: "Schedule",
    mono: true,
    value: f.expr,
    onChange: set('expr'),
    helper: "Cron expression, server time. 0 3 * * * is every day at 03:00."
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-label)',
      letterSpacing: '.08em',
      textTransform: 'uppercase',
      color: 'var(--text-tertiary)'
    }
  }, "Deliver to"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      flexWrap: 'wrap'
    }
  }, ['none', 'telegram', 'discord'].map(d => /*#__PURE__*/React.createElement(DS.Chip, {
    key: d,
    label: d === 'none' ? 'Session only' : d[0].toUpperCase() + d.slice(1),
    selected: f.deliver === d,
    onClick: () => set('deliver')(d)
  }))))));
}
Object.assign(window, {
  CronScreen,
  CronEditScreen,
  cronSeed
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/cron-screen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/drawer-overlay.jsx
try { (() => {
// Sessions drawer: search, Status/Cron destinations, sessions grouped by profile, archived view, long-press → rename/archive/delete
function DrawerOverlay({
  open,
  onClose,
  onSettings,
  onNew,
  onStatus,
  onCron,
  sessions,
  setSessions,
  active,
  onPick,
  persistent = false
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [q, setQ] = React.useState('');
  const [menu, setMenu] = React.useState(null);
  const [ask, setAsk] = React.useState(null);
  const [rename, setRename] = React.useState(null);
  const [archivedView, setArchivedView] = React.useState(false);
  const match = s => s.title.toLowerCase().includes(q.toLowerCase());
  const archived = sessions.filter(s => s.archived);
  const groups = ['default', 'research', 'ops'].map(p => ({
    p,
    items: sessions.filter(s => s.profile === p && !s.archived && match(s))
  })).filter(g => g.items.length);
  const header = /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "../../assets/mark.svg",
    alt: "",
    style: {
      height: 24
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-wordmark)',
      fontSize: 22,
      color: 'var(--text-body)'
    }
  }, "Tsukaima"), /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: 'var(--font-kanji)',
      fontSize: 11,
      color: 'var(--text-tertiary)'
    }
  }, "\u4F7F\u3044\u9B54")), /*#__PURE__*/React.createElement(DS.TextField, {
    value: q,
    onChange: setQ,
    placeholder: "Search sessions"
  }));
  const drawer = /*#__PURE__*/React.createElement(DS.Drawer, {
    width: persistent ? 300 : 296,
    style: {
      position: 'relative',
      flexShrink: 0
    },
    header: header,
    footer: /*#__PURE__*/React.createElement(DS.ConversationItem, {
      icon: "settings",
      title: "Settings",
      onClick: onSettings
    })
  }, archivedView ? /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(DS.ConversationItem, {
    icon: "arrow_back",
    title: "Archived",
    onClick: () => setArchivedView(false)
  }), archived.filter(match).map(s => /*#__PURE__*/React.createElement(DS.ConversationItem, {
    key: s.id,
    title: s.title,
    meta: s.profile,
    onClick: () => onPick(s.id),
    onLongPress: () => setMenu(s)
  })), !archived.length && /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      padding: '16px 12px'
    }
  }, "Nothing archived.")) : /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(DS.ConversationItem, {
    icon: "add",
    title: "New chat",
    onClick: onNew || onClose
  }), /*#__PURE__*/React.createElement(DS.ConversationItem, {
    icon: "monitor_heart",
    title: "Status",
    onClick: onStatus
  }), /*#__PURE__*/React.createElement(DS.ConversationItem, {
    icon: "schedule",
    title: "Cron",
    onClick: onCron
  }), groups.map(g => /*#__PURE__*/React.createElement(React.Fragment, {
    key: g.p
  }, /*#__PURE__*/React.createElement(DS.DrawerLabel, null, g.p, " profile"), g.items.map(s => /*#__PURE__*/React.createElement(DS.ConversationItem, {
    key: s.id,
    title: s.title,
    meta: s.meta,
    selected: active === s.id,
    onClick: () => onPick(s.id),
    onLongPress: () => setMenu(s)
  })))), !groups.length && /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      padding: '16px 12px'
    }
  }, "No sessions match."), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 8
    }
  }), /*#__PURE__*/React.createElement(DS.ConversationItem, {
    icon: "archive",
    title: "Archived",
    meta: archived.length ? String(archived.length) : undefined,
    onClick: () => setArchivedView(true)
  })), /*#__PURE__*/React.createElement(DS.Menu, {
    open: !!menu,
    title: menu && menu.title,
    onClose: () => setMenu(null),
    items: menu ? [{
      icon: 'edit',
      label: 'Rename',
      onClick: () => setRename({
        ...menu
      })
    }, menu.archived ? {
      icon: 'unarchive',
      label: 'Unarchive',
      onClick: () => setSessions(ss => ss.map(x => x.id === menu.id ? {
        ...x,
        archived: false
      } : x))
    } : {
      icon: 'archive',
      label: 'Archive',
      onClick: () => setSessions(ss => ss.map(x => x.id === menu.id ? {
        ...x,
        archived: true
      } : x))
    }, {
      icon: 'delete',
      label: 'Delete',
      danger: true,
      onClick: () => setAsk(menu)
    }] : []
  }), /*#__PURE__*/React.createElement(DS.Dialog, {
    open: !!ask,
    danger: true,
    title: "Delete session?",
    body: "Messages and tool output are removed from Hermes. This can\u2019t be undone.",
    confirmLabel: "Delete",
    onConfirm: () => {
      setSessions(ss => ss.filter(x => x.id !== ask.id));
      setAsk(null);
    },
    onCancel: () => setAsk(null)
  }), /*#__PURE__*/React.createElement(DS.Dialog, {
    open: !!rename,
    title: "Rename session",
    confirmLabel: "Save",
    onConfirm: () => {
      setSessions(ss => ss.map(x => x.id === rename.id ? {
        ...x,
        title: rename.title
      } : x));
      setRename(null);
    },
    onCancel: () => setRename(null),
    body: rename && /*#__PURE__*/React.createElement(DS.TextField, {
      value: rename.title,
      onChange: v => setRename(r => ({
        ...r,
        title: v
      }))
    })
  }));
  if (persistent) return drawer;
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      inset: 0,
      pointerEvents: open ? 'auto' : 'none',
      zIndex: 20,
      overflow: 'hidden',
      borderRadius: 'inherit'
    }
  }, /*#__PURE__*/React.createElement("div", {
    onClick: onClose,
    style: {
      position: 'absolute',
      inset: 0,
      background: 'rgba(39,34,32,.28)',
      opacity: open ? 1 : 0,
      transition: 'opacity var(--dur-base) var(--ease-quiet)'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      top: 0,
      bottom: 0,
      left: 0,
      transform: open ? 'none' : 'translateX(-104%)',
      transition: 'transform var(--dur-base) var(--ease-quiet)'
    }
  }, drawer));
}
Object.assign(window, {
  DrawerOverlay
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/drawer-overlay.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/logs-screen.jsx
try { (() => {
// GET /api/logs?file&level&lines — read-only tail
function LogsScreen({
  onBack
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [file, setFile] = React.useState('gateway');
  const [level, setLevel] = React.useState('info');
  const lines = {
    gateway: ['14:02:11 INFO  gateway: telegram poll ok (3 updates)', '14:02:14 INFO  session 8f2a: tool shell approved', '14:02:15 WARN  storage: token expires in 2 days', '14:02:19 INFO  gateway: discord heartbeat', '14:03:01 INFO  cron: inbox-triage fired', '14:03:04 INFO  session c71e: 1 842 tokens, 3.1s'],
    agent: ['14:02:14 INFO  agent: running shell `systemctl restart hermes-agent`', '14:02:15 INFO  agent: exit 0 (0.8s)', '14:02:16 INFO  agent: reply streamed 212 tokens']
  }[file].filter(l => level === 'info' || !l.includes(' INFO '));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      display: 'flex',
      flexDirection: 'column'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 4,
      padding: '8px 8px 2px'
    }
  }, /*#__PURE__*/React.createElement(DS.IconButton, {
    icon: "arrow_back",
    label: "Back",
    onClick: onBack
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-title)',
      color: 'var(--text-body)'
    }
  }, "Logs")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      padding: '10px 20px 6px',
      flexWrap: 'wrap'
    }
  }, ['gateway', 'agent'].map(f => /*#__PURE__*/React.createElement(DS.Chip, {
    key: f,
    label: f + '.log',
    selected: file === f,
    onClick: () => setFile(f)
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      width: 1,
      background: 'var(--border-hairline)',
      margin: '6px 2px'
    }
  }), ['info', 'warn'].map(l => /*#__PURE__*/React.createElement(DS.Chip, {
    key: l,
    label: l === 'info' ? 'All' : 'Warn+',
    selected: level === l,
    onClick: () => setLevel(l)
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflow: 'auto',
      margin: '8px 20px 20px'
    }
  }, /*#__PURE__*/React.createElement(DS.CodeBlock, {
    lang: file + '.log · last 200',
    code: lines.join('\n'),
    style: {
      margin: 0
    }
  })));
}
Object.assign(window, {
  LogsScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/logs-screen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/onboarding-screen.jsx
try { (() => {
function OnboardingScreen({
  onConnect
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 10,
      padding: '0 36px',
      textAlign: 'center',
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: 'var(--font-kanji)',
      fontSize: 14,
      color: 'var(--text-tertiary)',
      letterSpacing: '.2em',
      writingMode: 'vertical-rl',
      position: 'absolute',
      top: 48,
      right: 32
    }
  }, "\u4F7F\u3044\u9B54"), /*#__PURE__*/React.createElement("img", {
    src: "../../assets/mark.svg",
    alt: "",
    style: {
      height: 72,
      marginBottom: 14
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-wordmark)',
      fontSize: 38,
      color: 'var(--text-body)'
    }
  }, "Tsukaima"), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-status)',
      color: 'var(--text-secondary)',
      marginBottom: 6
    }
  }, "Your familiar is listening."), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      maxWidth: 260
    }
  }, "Bind this app to your Hermes instance to chat with your agent from anywhere."), /*#__PURE__*/React.createElement(DS.Button, {
    variant: "primary",
    style: {
      marginTop: 22
    },
    onClick: onConnect
  }, "Connect"), /*#__PURE__*/React.createElement("img", {
    src: "../../assets/resting-wisp.svg",
    alt: "",
    style: {
      width: 180,
      maxHeight: '18vh',
      marginTop: 40,
      opacity: .8
    }
  }));
}
Object.assign(window, {
  OnboardingScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/onboarding-screen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/settings-screen.jsx
try { (() => {
function SettingsScreen({
  onBack,
  onLogs,
  profile,
  setProfile,
  pairing,
  onPairing,
  theme,
  setTheme,
  offline,
  setOffline
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [url, setUrl] = React.useState('https://hermes.local:8000');
  const [token, setToken] = React.useState('hm_••••••••••••');
  const [model, setModel] = React.useState('Hermes 4 · 405B');
  const [dyn, setDyn] = React.useState(false);
  const [skills, setSkills] = React.useState({
    'Web search': true,
    'Shell': true,
    'Browser': false,
    'Memory': true
  });
  const [tools, setTools] = React.useState({
    'filesystem': true,
    'git': true,
    'docker': false
  });
  const Section = ({
    title,
    children,
    hint
  }) => /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-title)',
      fontSize: 17,
      color: 'var(--text-body)'
    }
  }, title), hint && /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      marginTop: -6
    }
  }, hint), children);
  const toggles = (obj, set) => Object.keys(obj).map(k => /*#__PURE__*/React.createElement(DS.Row, {
    key: k,
    title: k,
    trailing: /*#__PURE__*/React.createElement(DS.Switch, {
      checked: obj[k],
      onChange: v => set(o => ({
        ...o,
        [k]: v
      }))
    }),
    style: {
      margin: '0 -12px',
      minHeight: 48
    }
  }));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      display: 'flex',
      flexDirection: 'column'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 4,
      padding: '8px 8px 2px'
    }
  }, /*#__PURE__*/React.createElement(DS.IconButton, {
    icon: "arrow_back",
    label: "Back",
    onClick: onBack
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-title)',
      color: 'var(--text-body)'
    }
  }, "Settings")), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '14px 20px 24px',
      display: 'flex',
      flexDirection: 'column',
      gap: 28
    }
  }, /*#__PURE__*/React.createElement(Section, {
    title: "Hermes instance"
  }, /*#__PURE__*/React.createElement(DS.TextField, {
    label: "Server URL",
    mono: true,
    value: url,
    onChange: setUrl
  }), /*#__PURE__*/React.createElement(DS.TextField, {
    label: "Token",
    mono: true,
    value: token,
    onChange: setToken,
    helper: "Paste the token from your Hermes instance."
  })), /*#__PURE__*/React.createElement(Section, {
    title: "Profile",
    hint: "Scopes sessions, model and skills. Switching here changes everything else."
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      flexWrap: 'wrap'
    }
  }, ['default', 'research', 'ops'].map(p => /*#__PURE__*/React.createElement(DS.Chip, {
    key: p,
    label: p,
    selected: profile === p,
    onClick: () => setProfile(p)
  })))), /*#__PURE__*/React.createElement(Section, {
    title: "Model"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      flexWrap: 'wrap'
    }
  }, ['Hermes 4 · 405B', 'Hermes 4 · 70B', 'Hermes 3 · 8B'].map(m => /*#__PURE__*/React.createElement(DS.Chip, {
    key: m,
    label: m,
    selected: model === m,
    onClick: () => setModel(m)
  })))), /*#__PURE__*/React.createElement(Section, {
    title: "Skills",
    hint: "Toggle only. Installing new skills is a desktop task."
  }, /*#__PURE__*/React.createElement("div", null, toggles(skills, setSkills))), /*#__PURE__*/React.createElement(Section, {
    title: "Toolsets"
  }, /*#__PURE__*/React.createElement("div", null, toggles(tools, setTools))), /*#__PURE__*/React.createElement(Section, {
    title: "Pairing",
    hint: "Who may talk to the bot."
  }, pairing && /*#__PURE__*/React.createElement(DS.PairingCard, {
    platform: pairing.platform,
    who: pairing.who,
    code: pairing.code,
    onApprove: () => onPairing(true),
    onRevoke: () => onPairing(false),
    style: {
      margin: 0
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      margin: '0 -12px'
    }
  }, /*#__PURE__*/React.createElement(DS.Row, {
    icon: "send",
    title: "@mario",
    subtitle: "Telegram \xB7 paired 3 weeks ago",
    trailing: /*#__PURE__*/React.createElement(DS.Button, {
      size: "sm",
      variant: "ghost",
      danger: true
    }, "Revoke")
  }), /*#__PURE__*/React.createElement(DS.Row, {
    icon: "tag",
    title: "mario#0420",
    subtitle: "Discord \xB7 paired 2 months ago",
    trailing: /*#__PURE__*/React.createElement(DS.Button, {
      size: "sm",
      variant: "ghost",
      danger: true
    }, "Revoke")
  }))), /*#__PURE__*/React.createElement(Section, {
    title: "Diagnostics"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      margin: '0 -12px'
    }
  }, /*#__PURE__*/React.createElement(DS.Row, {
    icon: "receipt_long",
    title: "Logs",
    subtitle: "gateway.log \xB7 agent.log",
    trailingIcon: "chevron_right",
    onClick: onLogs
  }), /*#__PURE__*/React.createElement(DS.Row, {
    icon: "bar_chart",
    title: "Usage this month",
    subtitle: "1.2 M tokens \xB7 318 sessions \xB7 41 tool calls / day"
  }), /*#__PURE__*/React.createElement(DS.Row, {
    icon: "open_in_new",
    title: "Open admin dashboard",
    subtitle: "Config, env, MCP, webhooks \u2014 in the browser"
  }), /*#__PURE__*/React.createElement(DS.Row, {
    icon: "cloud_off",
    title: "Simulate Hermes unreachable",
    subtitle: "Design kit only \u2014 not in the real app",
    trailing: /*#__PURE__*/React.createElement(DS.Switch, {
      checked: !!offline,
      onChange: setOffline
    })
  }))), /*#__PURE__*/React.createElement(Section, {
    title: "Theme"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8
    }
  }, ['Warm', 'Dark', 'System'].map(t => /*#__PURE__*/React.createElement(DS.Chip, {
    key: t,
    label: t,
    selected: theme === t,
    onClick: () => setTheme(t)
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      color: 'var(--text-tertiary)',
      marginTop: -2
    }
  }, "System is the default: warm in light, near-black in dark."), /*#__PURE__*/React.createElement("div", {
    style: {
      margin: '0 -12px'
    }
  }, /*#__PURE__*/React.createElement(DS.Row, {
    title: "Material You dynamic color",
    subtitle: "Off keeps the fixed warm palette.",
    trailing: /*#__PURE__*/React.createElement(DS.Switch, {
      checked: dyn,
      onChange: setDyn
    })
  })))));
}
Object.assign(window, {
  SettingsScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/settings-screen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tsukaima/status-screen.jsx
try { (() => {
// Home: GET /api/status + POST /api/gateway/start|stop|restart, pending pairing, continue/new chat
function StatusScreen({
  onMenu,
  showMenu = true,
  gateway,
  onGateway,
  pairing,
  onPairing,
  lastSession,
  onContinue,
  onNew,
  onCron,
  onSettings,
  offline,
  onRetry
}) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [ask, setAsk] = React.useState(null);
  const [retrying, setRetrying] = React.useState(false);
  const retry = () => {
    setRetrying(true);
    setTimeout(() => {
      setRetrying(false);
      onRetry && onRetry();
    }, 1200);
  };
  const running = gateway === 'running';
  const facts = [{
    icon: 'dns',
    title: 'Hermes 0.9.4',
    subtitle: 'hermes.local:8000 · profile default'
  }, {
    icon: running ? 'play_circle' : 'stop_circle',
    title: running ? 'Gateway running' : 'Gateway stopped',
    subtitle: running ? 'up 41 days' : 'start it to receive messages',
    danger: !running
  }, {
    icon: 'forum',
    title: '2 platforms connected',
    subtitle: 'Telegram · Discord'
  }, {
    icon: 'chat',
    title: '3 active sessions',
    subtitle: '1 waiting for approval'
  }, {
    icon: 'memory',
    title: 'Memory 62% · Disk 41%',
    subtitle: '5.0 GB / 8 GB · 82 GB free'
  }];
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 4,
      padding: '8px 8px 2px'
    }
  }, showMenu && /*#__PURE__*/React.createElement(DS.IconButton, {
    icon: "menu",
    label: "Menu",
    onClick: onMenu
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      paddingLeft: showMenu ? 2 : 12
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "../../assets/mark.svg",
    alt: "",
    style: {
      height: 20
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--type-wordmark)',
      fontSize: 20,
      color: 'var(--text-body)'
    }
  }, "Tsukaima")), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }), /*#__PURE__*/React.createElement(DS.IconButton, {
    icon: "settings",
    label: "Settings",
    onClick: onSettings
  })), /*#__PURE__*/React.createElement(DS.StatusLine, {
    state: offline ? 'offline' : retrying ? 'tool' : running ? 'idle' : 'stopped',
    label: offline ? undefined : retrying ? 'Reconnecting' : running ? 'Hermes is listening' : undefined
  }), offline ? /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '8px 20px 20px',
      display: 'flex',
      flexDirection: 'column',
      gap: 16
    }
  }, /*#__PURE__*/React.createElement(DS.Card, {
    padding: 20
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-title)',
      fontSize: 19,
      marginBottom: 6
    }
  }, "Can\u2019t reach Hermes"), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--type-caption)',
      fontSize: 13.5,
      color: 'var(--text-secondary)',
      marginBottom: 16
    }
  }, "hermes.local:8000 didn\u2019t answer. Check the tunnel or that the box is awake. Cached sessions still open read-only."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(DS.Button, {
    variant: "primary",
    icon: "refresh",
    onClick: retry,
    disabled: retrying
  }, retrying ? 'Retrying…' : 'Retry'), /*#__PURE__*/React.createElement(DS.Button, {
    variant: "ghost",
    onClick: onSettings
  }, "Settings"))), lastSession && /*#__PURE__*/React.createElement(DS.Row, {
    icon: "history",
    title: lastSession,
    subtitle: "Open cached copy",
    trailingIcon: "chevron_right",
    onClick: onContinue,
    style: {
      margin: '0 -8px'
    }
  }), /*#__PURE__*/React.createElement(DS.DrawerLabel, null, "Last known status \xB7 14 min ago"), /*#__PURE__*/React.createElement("div", {
    style: {
      opacity: .55,
      margin: '0 -8px'
    }
  }, facts.slice(0, 3).map(f => /*#__PURE__*/React.createElement(DS.Row, {
    key: f.title,
    icon: f.icon,
    title: f.title,
    subtitle: f.subtitle
  })))) : /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: '8px 12px 20px',
      display: 'flex',
      flexDirection: 'column',
      gap: 6
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '4px 8px 2px',
      display: 'flex',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(DS.Button, {
    variant: "primary",
    icon: "add",
    onClick: onNew,
    style: {
      flex: 1
    }
  }, "New chat"), /*#__PURE__*/React.createElement(DS.Button, {
    icon: "schedule",
    onClick: onCron,
    style: {
      flex: 1
    }
  }, "Cron")), lastSession && /*#__PURE__*/React.createElement(DS.Row, {
    icon: "history",
    title: lastSession,
    subtitle: "Continue last session",
    trailingIcon: "chevron_right",
    onClick: onContinue
  }), pairing && /*#__PURE__*/React.createElement(DS.PairingCard, {
    platform: pairing.platform,
    who: pairing.who,
    code: pairing.code,
    onApprove: () => onPairing(true),
    onRevoke: () => onPairing(false),
    style: {
      margin: '6px 0'
    }
  }), /*#__PURE__*/React.createElement(DS.DrawerLabel, null, "Status"), facts.map(f => /*#__PURE__*/React.createElement(DS.Row, {
    key: f.title,
    icon: f.icon,
    title: f.title,
    subtitle: f.subtitle,
    danger: f.danger
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      padding: '8px 8px 0',
      flexWrap: 'wrap'
    }
  }, running ? /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(DS.Button, {
    icon: "restart_alt",
    onClick: () => setAsk('restart')
  }, "Restart gateway"), /*#__PURE__*/React.createElement(DS.Button, {
    variant: "ghost",
    danger: true,
    onClick: () => setAsk('stop')
  }, "Stop")) : /*#__PURE__*/React.createElement(DS.Button, {
    variant: "primary",
    icon: "play_arrow",
    onClick: () => onGateway('start')
  }, "Start gateway"))), /*#__PURE__*/React.createElement(DS.Dialog, {
    open: !!ask,
    danger: ask === 'stop',
    title: ask === 'stop' ? 'Stop the gateway?' : 'Restart the gateway?',
    body: ask === 'stop' ? 'Telegram and Discord will stop answering until you start it again.' : 'Platforms reconnect in a few seconds. Running sessions are kept.',
    confirmLabel: ask === 'stop' ? 'Stop' : 'Restart',
    onConfirm: () => {
      onGateway(ask);
      setAsk(null);
    },
    onCancel: () => setAsk(null)
  }));
}
Object.assign(window, {
  StatusScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tsukaima/status-screen.jsx", error: String((e && e.message) || e) }); }

__ds_ns.ApprovalCard = __ds_scope.ApprovalCard;

__ds_ns.AssistantMessage = __ds_scope.AssistantMessage;

__ds_ns.CodeBlock = __ds_scope.CodeBlock;

__ds_ns.Composer = __ds_scope.Composer;

__ds_ns.PairingCard = __ds_scope.PairingCard;

__ds_ns.THINKING_FLAIR = __ds_scope.THINKING_FLAIR;

__ds_ns.THINKING_FLAIR_WATCHLIST = __ds_scope.THINKING_FLAIR_WATCHLIST;

__ds_ns.StatusLine = __ds_scope.StatusLine;

__ds_ns.ToolRow = __ds_scope.ToolRow;

__ds_ns.UserBubble = __ds_scope.UserBubble;

__ds_ns.ConversationItem = __ds_scope.ConversationItem;

__ds_ns.Drawer = __ds_scope.Drawer;

__ds_ns.DrawerLabel = __ds_scope.DrawerLabel;

__ds_ns.TopBar = __ds_scope.TopBar;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.Card = __ds_scope.Card;

__ds_ns.Chip = __ds_scope.Chip;

__ds_ns.Dialog = __ds_scope.Dialog;

__ds_ns.Icon = __ds_scope.Icon;

__ds_ns.IconButton = __ds_scope.IconButton;

__ds_ns.Menu = __ds_scope.Menu;

__ds_ns.Row = __ds_scope.Row;

__ds_ns.Skeleton = __ds_scope.Skeleton;

__ds_ns.Switch = __ds_scope.Switch;

__ds_ns.TextField = __ds_scope.TextField;

})();
