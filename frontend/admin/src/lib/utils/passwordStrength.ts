// Lightweight password strength estimator. NOT a substitute for zxcvbn
// — we deliberately avoid pulling in that bundle (~400 KB) for a setup
// flow that's hit once per user. The score maps to a 0–4 bucket compatible
// with zxcvbn's API so swapping in the real thing later is a drop-in.
//
// Heuristics:
//   - Length tiers (the strongest single signal in any password study).
//   - Character-class diversity bumps the score, capped so a 6-char
//     "Aa1!" doesn't outscore a 14-char passphrase.
//   - Hard caps for common weak patterns: lone dictionary-ish words,
//     all-digit strings, sequential keyboard runs, repeated characters.

export type StrengthScore = 0 | 1 | 2 | 3 | 4;

export type StrengthResult = {
  score: StrengthScore;
  /** Human-readable label per score bucket. */
  label: "Very weak" | "Weak" | "Fair" | "Strong" | "Very strong";
  /** Short hint shown next to the meter. */
  hint: string;
};

const COMMON_BAD = new Set([
  "password",
  "passw0rd",
  "qwerty",
  "qwertyuiop",
  "letmein",
  "welcome",
  "admin",
  "administrator",
  "iloveyou",
  "monkey",
  "dragon",
  "abc123",
  "111111",
  "123456",
  "12345678",
  "123456789",
  "1234567890",
  "00000000",
  "passw0rd1",
]);

const SEQUENCES = [
  "abcdefghijklmnopqrstuvwxyz",
  "qwertyuiop",
  "asdfghjkl",
  "zxcvbnm",
  "0123456789",
];

function hasSequence(lower: string): boolean {
  if (lower.length < 4) return false;
  for (const seq of SEQUENCES) {
    for (let i = 0; i <= seq.length - 4; i++) {
      const slice = seq.slice(i, i + 4);
      if (lower.includes(slice)) return true;
      // Check reversed sequences too — "4321", "lkjh".
      const reversed = slice.split("").reverse().join("");
      if (lower.includes(reversed)) return true;
    }
  }
  return false;
}

function isRepeated(s: string): boolean {
  if (s.length < 4) return false;
  // Same character ≥ 4 in a row.
  return /(.)\1{3,}/.test(s);
}

export function estimatePassword(input: string): StrengthResult {
  const pw = input ?? "";
  if (pw.length === 0) {
    return { score: 0, label: "Very weak", hint: "Enter a password." };
  }

  const lower = pw.toLowerCase();

  // Cheap "this is famously bad" override before any positive scoring.
  if (COMMON_BAD.has(lower)) {
    return { score: 0, label: "Very weak", hint: "This is on every breach list — avoid it." };
  }

  let score = 0;
  // Length tiers.
  if (pw.length >= 8) score++;
  if (pw.length >= 12) score++;
  if (pw.length >= 16) score++;

  // Diversity — only counts if length is at least 8 (a short "Aa1!" is
  // still weak). Caps at +1 so the meter doesn't reward complexity
  // theatre.
  if (pw.length >= 8) {
    const classes =
      (/[a-z]/.test(pw) ? 1 : 0) +
      (/[A-Z]/.test(pw) ? 1 : 0) +
      (/\d/.test(pw) ? 1 : 0) +
      (/[^\w\s]/.test(pw) ? 1 : 0);
    if (classes >= 3) score++;
  }

  // Penalties — applied last so the meter doesn't promise "Strong" for
  // 12 zeros in a row.
  if (/^\d+$/.test(pw)) score = Math.min(score, 1);
  if (isRepeated(pw)) score = Math.min(score, 1);
  if (hasSequence(lower)) score = Math.min(score, 1);

  // Clamp to the 0–4 zxcvbn-compatible range.
  const finalScore = Math.max(0, Math.min(4, score)) as StrengthScore;

  return {
    score: finalScore,
    label: LABELS[finalScore],
    hint: HINTS[finalScore],
  };
}

const LABELS: Record<StrengthScore, StrengthResult["label"]> = {
  0: "Very weak",
  1: "Weak",
  2: "Fair",
  3: "Strong",
  4: "Very strong",
};

const HINTS: Record<StrengthScore, string> = {
  0: "Use a longer password with more variety.",
  1: "Add length or another character type.",
  2: "Decent — a few more characters helps.",
  3: "Strong password.",
  4: "Excellent password.",
};
