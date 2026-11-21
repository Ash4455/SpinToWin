import type { CampaignStatus } from '../../api/campaigns';

const LABEL: Record<CampaignStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '已上線',
  ENDED: '已結束',
};

export function StatusBadge({ status }: { status: CampaignStatus }) {
  return (
    <span className={`badge badge--${status.toLowerCase()}`}>
      {status} {LABEL[status]}
    </span>
  );
}
