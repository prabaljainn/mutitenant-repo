import type { Metadata } from "next";
import { JetBrains_Mono, Space_Grotesk } from "next/font/google";
import { type ReactNode } from "react";

import "./globals.css";

import { AuthBridge } from "@/lib/auth/AuthBridge";
import { AuthProvider } from "@/lib/auth/AuthProvider";
import { QueryProvider } from "@/lib/query/QueryProvider";
import { ThemeProvider } from "@/lib/theme/ThemeProvider";
import { ToastProvider } from "@/lib/toast/ToastProvider";
import { TweaksPanel } from "@/components/tweaks/TweaksPanel";

const ui = Space_Grotesk({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-ui-loaded",
});
const mono = JetBrains_Mono({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-mono-loaded",
});

export const metadata: Metadata = {
  title: "CloudGCS Admin",
  description: "Multi-tenant admin console for the Orochiverse platform.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  const tweaksEnabled = process.env.NEXT_PUBLIC_ENABLE_TWEAKS === "true";
  return (
    <html lang="en" className={`${ui.variable} ${mono.variable}`}>
      <body>
        <ThemeProvider>
          <QueryProvider>
            <AuthProvider>
              <ToastProvider>
                {/* AuthBridge wires the api/client to both AuthProvider
                    (token refresh) and ToastProvider (unreachable toast),
                    so it MUST sit inside both. Reorder with care. */}
                <AuthBridge>
                  {children}
                  {tweaksEnabled && <TweaksPanel />}
                </AuthBridge>
              </ToastProvider>
            </AuthProvider>
          </QueryProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
