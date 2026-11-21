import { describe, expect, it } from 'vitest';
import { campaignActions } from './campaignActions';

describe('campaignActions', () => {
  it('lets a DRAFT Campaign be edited, restructured and activated', () => {
    expect(campaignActions('DRAFT')).toEqual({
      editCampaign: true,
      activate: true,
      end: true,
      addPrize: true,
      deletePrize: true,
      editPrize: true,
      adjustStock: true,
      rebuildLiveState: false,
    });
  });

  it('lets an ACTIVE Campaign only adjust Prizes, end, and rebuild its live state', () => {
    expect(campaignActions('ACTIVE')).toEqual({
      editCampaign: false,
      activate: false,
      end: true,
      addPrize: false,
      deletePrize: false,
      editPrize: true,
      adjustStock: true,
      rebuildLiveState: true,
    });
  });

  it('allows nothing on an ENDED Campaign', () => {
    expect(Object.values(campaignActions('ENDED')).some(Boolean)).toBe(false);
  });
});
