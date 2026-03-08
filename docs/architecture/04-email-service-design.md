# Email Service Design

## 1. Overview

The platform needs a robust email service for:
- **Password reset** emails
- **User invitation** emails (when admin invites a user to a tenant)
- **Account verification** emails
- **System notifications** (tenant created, user role changed, etc.)
- **(Future)** Marketing / transactional emails per tenant

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    EMAIL SERVICE LAYER                        │
│                                                              │
│  ┌──────────────┐    ┌──────────────────┐                    │
│  │ EmailService  │───>│ TemplateEngine   │                    │
│  │ (interface)   │    │ (Thymeleaf)      │                    │
│  └──────┬───────┘    └──────────────────┘                    │
│         │                                                    │
│         ├──────────────────────────────────────┐             │
│         │                                      │             │
│  ┌──────▼───────┐  ┌─────────────┐   ┌────────▼──────────┐  │
│  │ SmtpProvider  │  │ SendGrid    │   │ AWS SES Provider  │  │
│  │ (dev/self-    │  │ Provider    │   │ (production)      │  │
│  │  hosted)      │  │             │   │                   │  │
│  └──────────────┘  └─────────────┘   └───────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Design Principles
1. **Provider-agnostic** — Switch between SMTP, SendGrid, or AWS SES via config
2. **Template-based** — All emails use Thymeleaf HTML templates (maintainable, consistent)
3. **Async by default** — Email sending is non-blocking (`@Async` + event-driven)
4. **Retry logic** — Failed emails are retried with exponential backoff
5. **Audit trail** — Every email sent is logged (who, what, when, status)

---

## 3. Provider Comparison

| Provider | Cost | Pros | Cons | Best For |
|----------|------|------|------|----------|
| **SMTP (self-hosted / Mailhog)** | Free | Full control; Mailhog for dev testing | Deliverability concerns; IP reputation | Local dev, testing |
| **SendGrid** | Free tier: 100/day | Easy API, good deliverability, analytics | Costs scale with volume | Startups, mid-scale |
| **AWS SES** | $0.10 per 1000 emails | Cheapest at scale, reliable | More setup (domain verification, sandbox) | Production at scale |
| **Resend** | Free tier: 100/day | Modern DX, React email templates | Newer, smaller ecosystem | Developer-focused projects |

### ✅ Recommendation

| Environment | Provider |
|-------------|----------|
| **Local Dev** | **Mailhog** (Docker container, catches all emails, web UI at `localhost:8025`) |
| **Production** | **AWS SES** or **SendGrid** — depends on your AWS usage. If already on AWS → SES. Otherwise → SendGrid |

---

## 4. Email Templates

All templates live in `platform/src/main/resources/templates/email/`:

| Template | Trigger | Variables |
|----------|---------|-----------|
| `password-reset.html` | Forgot password | `userName`, `resetLink`, `expiryMinutes` |
| `user-invitation.html` | Admin invites user to tenant | `inviterName`, `tenantName`, `inviteLink`, `roles` |
| `welcome.html` | User first login / registration | `userName`, `platformName` |
| `account-verification.html` | Email verification | `userName`, `verificationLink` |
| `password-changed.html` | Password successfully changed | `userName`, `changeTime` |
| `tenant-created.html` | New tenant created (to super admin) | `tenantName`, `createdBy` |

### Example Template Structure

```html
<!-- password-reset.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <div style="max-width: 600px; margin: 0 auto; font-family: sans-serif;">
    <h2>Reset Your Password</h2>
    <p>Hi <span th:text="${userName}">User</span>,</p>
    <p>Click the button below to reset your password. This link expires in
       <span th:text="${expiryMinutes}">60</span> minutes.</p>
    <a th:href="${resetLink}"
       style="background: #4F46E5; color: white; padding: 12px 24px;
              text-decoration: none; border-radius: 6px;">
      Reset Password
    </a>
    <p style="color: #6B7280; font-size: 12px; margin-top: 24px;">
      If you didn't request this, you can safely ignore this email.
    </p>
  </div>
</body>
</html>
```

---

## 5. Implementation

### 5.1 EmailService Interface

```java
public interface EmailService {

    void sendPasswordReset(String toEmail, String userName, String resetToken);

    void sendUserInvitation(String toEmail, String inviterName,
                           String tenantName, String inviteToken, List<String> roles);

    void sendWelcome(String toEmail, String userName);

    void sendAccountVerification(String toEmail, String userName, String verificationToken);

    void sendPasswordChanged(String toEmail, String userName);
}
```

### 5.2 Async + Event-Driven

```java
// Events trigger email sending
@Component
public class EmailEventListener {

    @Async
    @EventListener
    public void onPasswordResetRequested(PasswordResetEvent event) {
        emailService.sendPasswordReset(event.getEmail(), event.getUserName(),
                                       event.getResetToken());
    }

    @Async
    @EventListener
    public void onUserInvited(UserInvitedEvent event) {
        emailService.sendUserInvitation(...);
    }
}
```

### 5.3 Configuration

```yaml
# application.yml
app:
  email:
    provider: smtp  # smtp | sendgrid | ses
    from: "noreply@domain.com"
    from-name: "Platform"
    base-url: "https://domain.com"  # for generating links in emails

# application-dev.yml
spring:
  mail:
    host: localhost
    port: 1025  # Mailhog

# application-prod.yml (example for SendGrid)
app:
  email:
    provider: sendgrid
    sendgrid:
      api-key: ${SENDGRID_API_KEY}
```

---

## 6. Dev Environment — Mailhog

Mailhog is included in `deployment/docker-compose.yml`:
- **SMTP**: `localhost:1025` (catches all outgoing mail)
- **Web UI**: `localhost:8025` (view all captured emails in browser)
- Zero config — just point Spring Mail at `localhost:1025`

---

## 7. Questions for You

> [!IMPORTANT]
> **Q7:** Which email provider do you prefer for production? **AWS SES** (cheapest, need AWS account) or **SendGrid** (easy setup, generous free tier)?

> [!IMPORTANT]
> **Q8:** Should emails be **tenant-branded** (each tenant can customize email templates with their logo/colors), or is a **single platform branding** sufficient initially?
