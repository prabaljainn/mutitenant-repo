import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";

type Variant = "default" | "primary" | "ghost" | "danger";
type Size = "sm" | "md";

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  size?: Size;
  iconLeft?: ReactNode;
  iconOnly?: boolean;
};

const variantClass: Record<Variant, string> = {
  default: "btn",
  primary: "btn btn-primary",
  ghost: "btn btn-ghost",
  danger: "btn btn-danger",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "default", size = "md", iconLeft, iconOnly, className, children, ...rest },
  ref
) {
  const classes = [
    variantClass[variant],
    size === "sm" ? "btn-sm" : "",
    iconOnly ? "btn-icon" : "",
    className ?? "",
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <button ref={ref} className={classes} {...rest}>
      {iconLeft}
      {children}
    </button>
  );
});
