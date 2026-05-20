// Lightweight User-Agent → "Chrome on macOS" labeller. NOT a substitute
// for a real UA database; just enough to make the Active Sessions list
// recognisable. Matches the major modern browsers + a few mobile OSes.
// Order matters in the browser regexes — Edge/Opera must come before
// Chrome since they spoof "Chrome/..." in their UA strings.

const BROWSERS: Array<{ re: RegExp; name: string }> = [
  { re: /Edg\//, name: "Edge" },
  { re: /OPR\//, name: "Opera" },
  { re: /Firefox\//, name: "Firefox" },
  { re: /Chrome\//, name: "Chrome" },
  { re: /Version\/[\d.]+ Safari\//, name: "Safari" },
  { re: /curl\//, name: "curl" },
  { re: /Postman/, name: "Postman" },
];

const OS_MATCHERS: Array<{ re: RegExp; name: string }> = [
  { re: /Windows NT 10\.0/, name: "Windows" },
  { re: /Windows NT/, name: "Windows" },
  { re: /Mac OS X|Macintosh/, name: "macOS" },
  { re: /CrOS/, name: "ChromeOS" },
  { re: /Android/, name: "Android" },
  { re: /iPhone|iPad|iPod/, name: "iOS" },
  { re: /Linux/, name: "Linux" },
];

export type DeviceLabel = {
  /** Short human label — "Chrome on macOS", "Unknown device". */
  label: string;
  /** True if the UA matched a known mobile OS — lets the UI choose an icon. */
  mobile: boolean;
};

export function describeUserAgent(ua: string | null | undefined): DeviceLabel {
  if (!ua) return { label: "Unknown device", mobile: false };
  const trimmed = ua.trim();
  if (!trimmed) return { label: "Unknown device", mobile: false };

  const browser = BROWSERS.find((b) => b.re.test(trimmed))?.name;
  const os = OS_MATCHERS.find((m) => m.re.test(trimmed))?.name;
  const mobile = os === "Android" || os === "iOS";

  if (browser && os) return { label: `${browser} on ${os}`, mobile };
  if (browser) return { label: browser, mobile };
  if (os) return { label: os, mobile };
  return { label: "Unknown device", mobile };
}
