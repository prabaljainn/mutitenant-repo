import { type SVGProps } from "react";

// `stroke` lives in SVGProps as `string | undefined` (DOM attribute typing),
// so we omit it from the intersection and re-add it as a number below — that
// avoids `string & number = never`, which TypeScript otherwise infers and then
// rejects every numeric stroke we pass in.
export type IconProps = {
  d: string;
  size?: number;
  stroke?: number;
} & Omit<SVGProps<SVGSVGElement>, "width" | "height" | "stroke">;

export function Icon({ d, size = 16, stroke = 1.6, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      {...rest}
    >
      <path d={d} />
    </svg>
  );
}
