// Fallback for when middleware doesn't run (e.g. static export). The admin
// layout takes over from /overview and decides whether to bounce to /login.
import { redirect } from "next/navigation";

export default function RootPage() {
  redirect("/overview");
}
