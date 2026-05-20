import { Spinner } from "./Spinner";

export type PageLoaderProps = {
  /** Optional sub-line ("Restoring session…", "Checking access…"). */
  label?: string;
};

/**
 * Full-viewport branded loader. Used while the auth provider rehydrates
 * from localStorage so a real-page refresh shows something on-brand
 * instead of a blank white screen. Matches the auth-shell visuals:
 * the same logo block, the same accent colour, the same font.
 */
export function PageLoader({ label = "Restoring your session…" }: PageLoaderProps) {
  return (
    <div className="page-loader" role="status" aria-live="polite">
      <div className="page-loader-mark">
        <div className="sb-logo">S</div>
      </div>
      <Spinner size={22} />
      <div className="page-loader-label">{label}</div>
    </div>
  );
}
