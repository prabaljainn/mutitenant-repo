import { OperatorDetail } from "./OperatorDetail";

export default async function OperatorDetailPage({
  params,
}: {
  params: Promise<{ operatorId: string }>;
}) {
  const { operatorId } = await params;
  return <OperatorDetail operatorId={operatorId} />;
}
