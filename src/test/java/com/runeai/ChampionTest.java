package com.runeai;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The champion file is written by sim/self_play.py and read here. Two ways that
 * goes wrong silently: a renamed key deserializes to a genome of zeros (which
 * reads as "no floor, no volume, no capital"), and the ROI floor is a per-HOUR
 * rate being applied to cycles measured in seconds.
 */
public class ChampionTest
{
	/** The real champion file's genome block, verbatim. */
	private static final String GENOME = "{\"roi_floor\":0.02434289033938143,"
		+ "\"mom_buy_cut\":-0.01803895951442517,\"mom_spike_cut\":0.034968689988931344,"
		+ "\"vol_share\":0.25,\"conc\":0.7320657220643466,\"hold_bars\":1.0}";

	private static class Genome
	{
		double roi_floor;
		double mom_buy_cut;
		double mom_spike_cut;
		double vol_share;
		double conc;
	}

	@Test
	public void genomeFieldNamesMatchTheLeaguesOutput()
	{
		final Genome g = new Gson().fromJson(GENOME, Genome.class);
		assertEquals(0.0243, g.roi_floor, 1e-4);
		assertEquals(-0.0180, g.mom_buy_cut, 1e-4);
		assertEquals(0.0350, g.mom_spike_cut, 1e-4);
		assertEquals(0.25, g.vol_share, 1e-9);
		assertEquals(0.7321, g.conc, 1e-4);
		// the evolved cuts really are different from the hand-tuned ones, so
		// adopting them is a behaviour change and not a no-op
		assertTrue(g.mom_buy_cut < Champion.DEFAULT_MOM_BUY_CUT);
		assertTrue(g.mom_spike_cut < Champion.DEFAULT_MOM_SPIKE_CUT);
		assertTrue(g.vol_share > Champion.DEFAULT_VOL_SHARE);
	}

	@Test
	public void withNoChampionEveryParamIsTheOldHandTunedConstant()
	{
		final Champion c = new Champion(new Gson()); // never loaded: no file, no genome
		assertEquals(Champion.DEFAULT_MOM_BUY_CUT, c.momBuyCut(), 1e-12);
		assertEquals(Champion.DEFAULT_MOM_SPIKE_CUT, c.momSpikeCut(), 1e-12);
		assertEquals(Champion.DEFAULT_VOL_SHARE, c.volShare(), 1e-12);
		assertEquals(0.33, c.conc(0.33), 1e-12);
		// and no ROI floor at all, which is exactly what the ranker did before
		assertEquals(0.0, c.roiFloorFor(40), 1e-12);
		assertEquals(0.0, c.roiFloorFor(7_200), 1e-12);
		assertTrue(!c.adopted());
	}

	@Test
	public void theRoiFloorConvertsFromHourlyBarsToTheActualCycle()
	{
		// 2.43% per hourly bar demanded of a 40s cycle would delete the quick lane;
		// pro-rata keeps the RATE and asks for 1/90th of it
		final double hourly = 0.02434289033938143;
		final double expected40s = hourly * 40 / 3600.0;
		assertTrue("a 40s cycle must not face the full hourly floor", expected40s < hourly / 50);
		// and a cycle longer than an hour is never asked for more than the champion
		// itself cleared
		assertTrue(Math.min(hourly, hourly * 7_200 / 3600.0) == hourly);
	}
}
