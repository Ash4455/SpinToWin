import { useState, type FormEvent } from 'react';
import type { AdminPrize } from '../../api/adminCampaigns';
import { AdminAlert } from './AdminAlert';
import { fieldErrorsOf } from './errors';
import { Field } from './Field';
import { ProbabilitySummary } from './ProbabilitySummary';
import { checkProbabilityInput, percentToPpm, ppmToPercent, ppmToPercentText } from './probability';
import { useAction } from './useAction';

/** 業務錯誤碼 → 表單欄位 */
const PROBABILITY_CODE_FIELDS = { PROBABILITY_SUM_EXCEEDED: 'winProbability' };
const STOCK_CODE_FIELDS = { STOCK_NEGATIVE: 'delta' };

const ppmsOf = (prizes: readonly AdminPrize[]) => prizes.map((p) => percentToPpm(p.winProbability));

interface AddProps {
  /** Campaign 目前所有的 Prize（計算加入後的總和） */
  prizes: readonly AdminPrize[];
  onAdd: (prize: { name: string; stock: number | null; winProbability: number }) => Promise<unknown>;
}

/** 新增 Prize（DRAFT）：名稱、初始 Stock、Win Probability，輸入時即時顯示加入後的總和。 */
export function AddPrizeForm({ prizes, onAdd }: AddProps) {
  const [name, setName] = useState('');
  const [stock, setStock] = useState('');
  const [probability, setProbability] = useState('');
  const action = useAction();
  const errors = fieldErrorsOf(action.error, PROBABILITY_CODE_FIELDS);
  const check = checkProbabilityInput(probability, ppmsOf(prizes));

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!check.canSubmit || check.ppm === null) return;
    const ppm = check.ppm;
    const added = await action.run(() =>
      onAdd({ name: name.trim(), stock: stock.trim() === '' ? null : Number(stock), winProbability: ppmToPercent(ppm) }),
    );
    if (added) {
      setName('');
      setStock('');
      setProbability('');
    }
  }

  return (
    <form className="admin-form" aria-label="新增 Prize" onSubmit={(e) => void handleSubmit(e)} noValidate>
      <fieldset className="admin-form__fields" disabled={action.pending}>
        <Field label="名稱" value={name} onChange={(e) => setName(e.target.value)} error={errors.name} maxLength={100} />
        <Field
          label="Stock"
          type="number"
          min={0}
          step={1}
          value={stock}
          onChange={(e) => setStock(e.target.value)}
          error={errors.stock}
        />
        <Field
          label="Win Probability（%）"
          inputMode="decimal"
          placeholder="例如 0.0001"
          value={probability}
          onChange={(e) => setProbability(e.target.value)}
          error={check.error ?? errors.winProbability}
          hint="0～100，最多 4 位小數"
        />
      </fieldset>
      {probability.trim() !== '' && <ProbabilitySummary prizePpms={check.prizePpms} />}
      <AdminAlert error={action.error} shownFields={['name', 'stock', 'winProbability']} />
      <div className="admin-form__actions">
        <button type="submit" className="button" disabled={!check.canSubmit || action.pending}>
          新增 Prize
        </button>
      </div>
    </form>
  );
}

interface EditProps {
  prize: AdminPrize;
  prizes: readonly AdminPrize[];
  onSave: (prize: { name: string; winProbability: number }) => Promise<unknown>;
  onCancel: () => void;
}

/** 修改 Prize 的名稱與 Win Probability（DRAFT、ACTIVE）；總和以換掉這個 Prize 的機率後計算。 */
export function EditPrizeForm({ prize, prizes, onSave, onCancel }: EditProps) {
  const [name, setName] = useState(prize.name);
  const [probability, setProbability] = useState(ppmToPercentText(percentToPpm(prize.winProbability)));
  const action = useAction();
  const errors = fieldErrorsOf(action.error, PROBABILITY_CODE_FIELDS);
  const check = checkProbabilityInput(
    probability,
    ppmsOf(prizes.filter((p) => p.id !== prize.id)),
  );

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!check.canSubmit || check.ppm === null) return;
    const ppm = check.ppm;
    void action.run(() => onSave({ name: name.trim(), winProbability: ppmToPercent(ppm) }));
  }

  return (
    <form className="admin-form admin-form--inline" aria-label={`修改 Prize：${prize.name}`} onSubmit={handleSubmit} noValidate>
      <fieldset className="admin-form__fields" disabled={action.pending}>
        <Field label="名稱" value={name} onChange={(e) => setName(e.target.value)} error={errors.name} maxLength={100} />
        <Field
          label="Win Probability（%）"
          inputMode="decimal"
          value={probability}
          onChange={(e) => setProbability(e.target.value)}
          error={check.error ?? errors.winProbability}
          hint="0～100，最多 4 位小數"
        />
      </fieldset>
      <ProbabilitySummary prizePpms={check.prizePpms} />
      <AdminAlert error={action.error} shownFields={['name', 'winProbability']} />
      <div className="admin-form__actions">
        <button type="submit" className="button" disabled={!check.canSubmit || action.pending}>
          儲存
        </button>
        <button type="button" className="button button--secondary" onClick={onCancel}>
          取消
        </button>
      </div>
    </form>
  );
}

const DELTA_PATTERN = /^[+-]?\d+$/;

interface StockProps {
  prize: AdminPrize;
  onAdjust: (delta: number) => Promise<unknown>;
}

/** Stock 增減量（例如 +50、-10），不覆寫 Stock；會讓剩餘 Stock 變負數的增減量直接擋下。 */
export function StockDeltaControl({ prize, onAdjust }: StockProps) {
  const [input, setInput] = useState('');
  const action = useAction();
  const trimmed = input.trim();
  const delta = DELTA_PATTERN.test(trimmed) ? Number(trimmed) : null;

  // 顯示的剩餘 Stock 只會比 Redis 的即時值多（扣減稍後才寫回 MySQL），所以這裡擋下的後端也一定會拒絕
  let localError: string | undefined;
  if (trimmed !== '' && (delta === null || delta === 0)) localError = '請輸入非 0 的整數，例如 +50 或 -10';
  else if (delta !== null && prize.remainingStock + delta < 0) localError = `Stock 不可變成負數（剩餘 ${prize.remainingStock}）`;

  const backendErrors = fieldErrorsOf(action.error, STOCK_CODE_FIELDS);
  const canSubmit = delta !== null && delta !== 0 && !localError && !action.pending;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!canSubmit || delta === null) return;
    if (await action.run(() => onAdjust(delta))) setInput('');
  }

  return (
    <form className="stock-delta" aria-label={`Stock 增減：${prize.name}`} onSubmit={(e) => void handleSubmit(e)} noValidate>
      <Field
        label="Stock 增減量"
        className="field--compact"
        inputMode="numeric"
        placeholder="+50 / -10"
        size={8}
        value={input}
        onChange={(e) => {
          setInput(e.target.value);
          action.reset();
        }}
        error={localError ?? backendErrors.delta}
        disabled={action.pending}
      />
      <button type="submit" className="button button--secondary button--small" disabled={!canSubmit}>
        套用
      </button>
      {action.error !== null && backendErrors.delta === undefined && <AdminAlert error={action.error} />}
    </form>
  );
}
