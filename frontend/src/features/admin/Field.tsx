import { useId, type InputHTMLAttributes } from 'react';

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  /** 顯示在欄位下方的錯誤（後端 errors[] 或前端檢查） */
  error?: string;
  /** 欄位下方的說明，沒有錯誤時顯示 */
  hint?: string;
}

/** 有標籤的輸入欄位；錯誤以 aria-describedby 連到欄位上。 */
export function Field({ label, error, hint, className, ...input }: Props) {
  const id = useId();
  const noteId = `${id}-note`;
  const note = error ?? hint;
  return (
    <div className={`field${error ? ' field--invalid' : ''}${className ? ` ${className}` : ''}`}>
      <label htmlFor={id} className="field__label">
        {label}
      </label>
      <input
        id={id}
        className="field__input"
        aria-invalid={error ? true : undefined}
        aria-describedby={note ? noteId : undefined}
        {...input}
      />
      {note && (
        <span id={noteId} className={error ? 'field__error' : 'field__hint'}>
          {note}
        </span>
      )}
    </div>
  );
}
