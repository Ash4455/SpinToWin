import { useState, type FormEvent } from 'react';
import type { AdminCampaign, CampaignRequest } from '../../api/adminCampaigns';
import { AdminAlert } from './AdminAlert';
import { isoToLocalInput, localInputToIso } from './dateTime';
import { fieldErrorsOf } from './errors';
import { Field } from './Field';
import { useAction } from './useAction';

const FIELDS = ['name', 'startAt', 'endAt', 'drawQuota'] as const;

interface Props {
  /** 修改時的現有設定；建立時省略 */
  campaign?: AdminCampaign;
  submitLabel: string;
  /** 不允許修改（Campaign Status 不是 DRAFT）時鎖住整個表單 */
  disabled?: boolean;
  disabledReason?: string;
  onSubmit: (request: CampaignRequest) => Promise<unknown>;
}

/** Campaign 的名稱、時間窗（本地時間輸入、以 UTC 送出）與 Draw Quota。 */
export function CampaignForm({ campaign, submitLabel, disabled = false, disabledReason, onSubmit }: Props) {
  const [name, setName] = useState(campaign?.name ?? '');
  const [startAt, setStartAt] = useState(campaign ? isoToLocalInput(campaign.startAt) : '');
  const [endAt, setEndAt] = useState(campaign ? isoToLocalInput(campaign.endAt) : '');
  const [drawQuota, setDrawQuota] = useState(campaign ? String(campaign.drawQuota) : '');
  const action = useAction();
  const errors = fieldErrorsOf(action.error);

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const quota = drawQuota.trim();
    void action.run(() =>
      onSubmit({
        name: name.trim(),
        startAt: localInputToIso(startAt),
        endAt: localInputToIso(endAt),
        drawQuota: quota === '' ? null : Number(quota),
      }),
    );
  }

  return (
    <form className="admin-form" onSubmit={handleSubmit} noValidate>
      <fieldset disabled={disabled || action.pending} className="admin-form__fields">
        <Field label="名稱" value={name} onChange={(e) => setName(e.target.value)} error={errors.name} maxLength={100} />
        <Field label="開始時間" type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} error={errors.startAt} />
        <Field label="結束時間" type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} error={errors.endAt} />
        <Field
          label="Draw Quota（每人抽獎次數上限）"
          type="number"
          min={1}
          step={1}
          value={drawQuota}
          onChange={(e) => setDrawQuota(e.target.value)}
          error={errors.drawQuota}
        />
      </fieldset>
      <AdminAlert error={action.error} shownFields={FIELDS} />
      <div className="admin-form__actions">
        <button type="submit" className="button" disabled={disabled || action.pending}>
          {action.pending ? '送出中…' : submitLabel}
        </button>
        {disabled && disabledReason && <span className="muted">{disabledReason}</span>}
      </div>
    </form>
  );
}
