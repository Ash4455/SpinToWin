import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { describeAdminError, fieldErrorsOf } from './errors';

const validationFailed = new ApiError({
  title: 'Bad Request',
  status: 400,
  code: 'VALIDATION_FAILED',
  detail: 'Request validation failed.',
  errors: [
    { field: 'endAt', message: 'must be after startAt' },
    { field: 'drawQuota', message: 'must be greater than 0' },
    { field: 'drawQuota', message: 'must not be null' },
  ],
});

describe('fieldErrorsOf', () => {
  it('maps errors[] to the form fields, joining several messages of one field', () => {
    expect(fieldErrorsOf(validationFailed)).toEqual({
      endAt: 'must be after startAt',
      drawQuota: 'must be greater than 0；must not be null',
    });
  });

  it('puts a business error code on the field it concerns', () => {
    const sumExceeded = new ApiError({
      title: 'Conflict',
      status: 409,
      code: 'PROBABILITY_SUM_EXCEEDED',
      detail: 'Total win probability would be 100.5000%',
    });

    expect(fieldErrorsOf(sumExceeded, { PROBABILITY_SUM_EXCEEDED: 'winProbability' })).toEqual({
      winProbability: '所有 Prize 的 Win Probability 總和不可超過 100%',
    });
  });

  it('has no field errors for other failures', () => {
    expect(fieldErrorsOf(new ApiError({ title: 'Not Found', status: 404, code: 'CAMPAIGN_NOT_FOUND' }))).toEqual({});
    expect(fieldErrorsOf(new Error('boom'))).toEqual({});
    expect(fieldErrorsOf(undefined)).toEqual({});
  });
});

describe('describeAdminError', () => {
  it('explains a known code in the domain language and keeps the backend detail', () => {
    const notEditable = new ApiError({
      title: 'Conflict',
      status: 409,
      code: 'CAMPAIGN_NOT_EDITABLE',
      detail: 'Campaign 7 is ACTIVE',
    });

    expect(describeAdminError(notEditable)).toEqual({
      title: '目前的 Campaign Status 不允許這項修改',
      detail: 'Campaign 7 is ACTIVE',
      code: 'CAMPAIGN_NOT_EDITABLE',
    });
  });

  it('falls back to the Problem Details title for an unknown code', () => {
    expect(describeAdminError(new ApiError({ title: 'Gateway Timeout', status: 504 }))).toEqual({
      title: 'Gateway Timeout',
      detail: undefined,
      code: undefined,
    });
  });

  it('describes a non-API failure', () => {
    expect(describeAdminError(new Error('boom'))).toEqual({ title: '發生未預期的錯誤', detail: 'boom', code: undefined });
  });
});
