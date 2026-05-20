// Tenant detail entry route — the design uses tabs rather than nested URLs
// for the three sub-sections (Overview / Members / Settings). The actual UI
// lives in ./TenantDetail.tsx (client component) so this page is a thin
// async server wrapper that just hands off the tenantId param.

import { TenantDetail } from "./TenantDetail";

export default async function TenantDetailPage({
  params,
}: {
  params: Promise<{ tenantId: string }>;
}) {
  const { tenantId } = await params;
  return <TenantDetail tenantId={tenantId} />;
}
