import { forwardRef, type InputHTMLAttributes } from "react";

export type InputProps = InputHTMLAttributes<HTMLInputElement> & {
  mono?: boolean;
};

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { mono, className, ...rest },
  ref
) {
  const classes = ["input", mono ? "mono" : "", className ?? ""].filter(Boolean).join(" ");
  return <input ref={ref} className={classes} {...rest} />;
});
