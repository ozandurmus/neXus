import { cloneElement, useEffect, useRef, useState, type ReactElement, type Ref, type SyntheticEvent } from "react";
import Tooltip, { type TooltipProps } from "@mui/material/Tooltip";

/**
 * A MUI Tooltip that is built on first pointer contact.
 *
 * A closed MUI Tooltip is not free: it is a dozen hooks, four slot lookups and a styled Popper that renders nothing. A
 * table row that carries three of them (the vendor badge, the validation chip, the deviation chip) made a 50-row page
 * most of a second of render time. Here a row carries a plain element until somebody points at it.
 *
 * Until then the child keeps what MUI would have given it (the string title as its `aria-label`, unless it names itself)
 * and listens for the first `mouseover` or `touchstart`. That event builds the real Tooltip around the child and replays
 * the event on it once built, so the real Tooltip's own delay and hover rules apply from the first touch on; nothing about
 * the tooltip is reimplemented here. The child element is built again at that moment (its DOM node is replaced), which
 * matters to a child that holds state or keyboard focus: give this component static, non-focusable children (a link or a
 * button inside keeps the plain Tooltip).
 *
 * `describeChild` changes what MUI puts on the child, so it falls back to the plain Tooltip.
 */
type Contact = "mouseover" | "touchstart";

export function LazyTooltip(props: TooltipProps) {
  const { title, children, describeChild } = props;
  const [armed, setArmed] = useState<Contact | null>(null);
  const node = useRef<HTMLElement | null>(null);
  const child = children as ReactElement<Record<string, unknown> & { ref?: Ref<HTMLElement> }>;
  const childRef = (child as unknown as { ref?: Ref<HTMLElement> }).ref;

  // True while the contact that built the Tooltip is replayed: the child's own handler already ran for the real event.
  const replaying = useRef(false);

  // Once the real Tooltip is mounted, deliver the contact that built it, exactly once.
  useEffect(() => {
    if (!armed || !node.current) return;
    const target = node.current;
    replaying.current = true;
    try {
      target.dispatchEvent(armed === "mouseover" ? new MouseEvent("mouseover", { bubbles: true }) : new Event("touchstart", { bubbles: true }));
    } finally {
      replaying.current = false;
    }
  }, [armed]);

  const setRef = (value: HTMLElement | null) => {
    node.current = value;
    if (typeof childRef === "function") childRef(value);
    else if (childRef && typeof childRef === "object") (childRef as { current: HTMLElement | null }).current = value;
  };

  if (describeChild) return <Tooltip {...props} />;
  const own = child.props;
  const once = (handler: unknown) => (typeof handler === "function" ? (event: SyntheticEvent) => { if (!replaying.current) handler(event); } : undefined);
  if (armed) {
    return <Tooltip {...props}>{cloneElement(child, { ref: setRef, ...(own.onMouseOver ? { onMouseOver: once(own.onMouseOver) } : {}), ...(own.onTouchStart ? { onTouchStart: once(own.onTouchStart) } : {}) })}</Tooltip>;
  }

  const arm = (contact: Contact) => (event: SyntheticEvent) => {
    const handler = own[contact === "mouseover" ? "onMouseOver" : "onTouchStart"];
    if (typeof handler === "function") handler(event);
    setArmed(contact);
  };
  return cloneElement(child, {
    ...(typeof title === "string" && own["aria-label"] === undefined ? { "aria-label": title } : {}),
    onMouseOver: arm("mouseover"), onTouchStart: arm("touchstart"),
    ref: setRef,
  });
}
