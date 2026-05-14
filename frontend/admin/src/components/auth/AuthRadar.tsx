// Animated radar visual that fills the right pane of the login screen.
// Pure CSS — sweep + pulse keyframes already live in globals.css.

export function AuthRadar() {
  return (
    <div className="auth-visual">
      <div className="auth-visual-grid" />
      <div className="auth-radar">
        <div className="auth-radar-sweep" />
        <span className="auth-radar-blip" style={{ top: "32%", left: "60%" }} />
        <span className="auth-radar-blip" style={{ top: "55%", left: "40%", animationDelay: "0.6s" }} />
        <span className="auth-radar-blip" style={{ top: "70%", left: "64%", animationDelay: "1.1s" }} />
      </div>
      <div className="auth-visual-caption">
        <b>CLOUDGCS · LIVE</b>
        <span>3 active drones · 1 tenant</span>
      </div>
    </div>
  );
}
