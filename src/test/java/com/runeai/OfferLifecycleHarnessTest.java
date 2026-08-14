package com.runeai;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GrandExchangeOfferChanged;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;

/**
 * The off-client lifecycle rig: drives the REAL {@code onGrandExchangeOfferChanged}
 * through the exact event sequences a live client produces, without launching
 * RuneLite. The whole test JVM runs with a sandboxed {@code user.home} (see
 * build.gradle), so the ledgers these scenarios write can never touch the real
 * {@code ~/.runelite}.
 *
 * <p>Scenario B is the one that used to lose money silently: an offer that
 * completes while the client is closed re-fires in a terminal state on login,
 * and before the fix no track existed for it, so nothing was ever booked.
 */
public class OfferLifecycleHarnessTest
{
	private Client client;

	@Before
	public void sandboxIsClean() throws Exception
	{
		final String home = System.getProperty("user.home");
		assertTrue("tests must run with the sandboxed home from build.gradle, got " + home,
			home.replace('\\', '/').contains("/build/test-home"));
		AsyncWriter.flush(2_000); // let stragglers land before we wipe
		final File dir = new File(new File(home, ".runelite"), "runeai");
		dir.mkdirs();
		final File[] files = dir.listFiles();
		if (files != null)
		{
			for (File f : files)
			{
				f.delete();
			}
		}
	}

	// ================= reflection plumbing =================

	private static void set(Object target, String field, Object value) throws Exception
	{
		final Field f = RuneAIPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Object get(Object target, String field) throws Exception
	{
		final Field f = RuneAIPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(target);
	}

	private static void call(Object target, String method) throws Exception
	{
		final Method m = RuneAIPlugin.class.getDeclaredMethod(method);
		m.setAccessible(true);
		m.invoke(target);
	}

	private RuneAIPlugin newPlugin(Gson gson, ItemMemory memory, FlipService svc, long sessionStartMs)
		throws Exception
	{
		final RuneAIPlugin p = new RuneAIPlugin();
		client = mock(Client.class);
		final ItemComposition comp = mock(ItemComposition.class);
		when(comp.getName()).thenReturn("Testium");
		when(client.getItemDefinition(anyInt())).thenReturn(comp);
		set(p, "client", client);
		set(p, "gson", gson);
		set(p, "itemMemory", memory);
		set(p, "flipService", svc);
		set(p, "itemManager", mock(net.runelite.client.game.ItemManager.class));
		set(p, "panel", mock(RuneAIPanel.class));
		set(p, "overlay", mock(RuneAIOverlay.class));
		set(p, "mascot", mock(MascotOverlay.class));
		set(p, "voice", mock(VoicePlayer.class));
		set(p, "config", new RuneAIConfig() {});
		set(p, "sessionStartMs", sessionStartMs);
		return p;
	}

	private static FlipService service(Gson gson, ItemMemory memory)
	{
		return new FlipService(new OkHttpClient(), gson, memory,
			new FillTimeModel(gson), new GeBrain(gson), new Champion(gson));
	}

	private static GrandExchangeOffer offer(int item, int price, int qtySold, int spent,
		GrandExchangeOfferState st, int total)
	{
		return new GrandExchangeOffer()
		{
			@Override
			public int getQuantitySold()
			{
				return qtySold;
			}

			@Override
			public int getItemId()
			{
				return item;
			}

			@Override
			public int getTotalQuantity()
			{
				return total;
			}

			@Override
			public int getPrice()
			{
				return price;
			}

			@Override
			public int getSpent()
			{
				return spent;
			}

			@Override
			public GrandExchangeOfferState getState()
			{
				return st;
			}
		};
	}

	private static void fire(RuneAIPlugin p, int slot, GrandExchangeOffer o)
	{
		final GrandExchangeOfferChanged ev = new GrandExchangeOfferChanged();
		ev.setSlot(slot);
		ev.setOffer(o);
		p.onGrandExchangeOfferChanged(ev);
	}

	@SuppressWarnings("unchecked")
	private static long[] basisOf(RuneAIPlugin p, int itemId) throws Exception
	{
		return ((Map<Integer, long[]>) get(p, "flipBasis")).get(itemId);
	}

	// ================= scenario A: a whole flip, watched live =================

	@Test
	public void aWatchedBuyThenSellBooksTheProfitEverywhere() throws Exception
	{
		final Gson gson = new Gson();
		final ItemMemory memory = new ItemMemory(gson);
		final RuneAIPlugin p = newPlugin(gson, memory, service(gson, memory),
			System.currentTimeMillis() - 1_000);

		fire(p, 0, offer(4151, 100, 0, 0, GrandExchangeOfferState.BUYING, 10));
		fire(p, 0, offer(4151, 100, 10, 1_000, GrandExchangeOfferState.BOUGHT, 10));
		final long[] basis = basisOf(p, 4151);
		assertNotNull("a filled buy must open a basis", basis);
		assertEquals(10, basis[0]);
		assertEquals(1_000, basis[1]);

		fire(p, 0, offer(4151, 120, 0, 0, GrandExchangeOfferState.SELLING, 10));
		fire(p, 0, offer(4151, 120, 10, 1_200, GrandExchangeOfferState.SOLD, 10));

		// 1,200 gross − 20 tax (2 gp x 10, floored per unit) − 1,000 cost = 180
		assertEquals(180L, get(p, "flipRealized"));
		assertEquals(180L, get(p, "lifetimeRealized"));
		assertEquals(10L, get(p, "unitsBought"));
		assertEquals(10L, get(p, "unitsSold"));
		assertEquals(2, get(p, "sessionFills"));
		final long[] after = basisOf(p, 4151);
		assertTrue("a sold-through basis is spent", after == null || after[0] == 0);
	}

	// ================= scenario B: the fill that happened offline =================

	@Test
	public void anOfferThatCompletedWhileTheClientWasClosedIsStillBooked() throws Exception
	{
		final Gson gson = new Gson();
		final ItemMemory memory = new ItemMemory(gson);
		final FlipService svc = service(gson, memory);

		// run 1: the offer is PLACED and the client dies before it fills
		final RuneAIPlugin run1 = newPlugin(gson, memory, svc, System.currentTimeMillis() - 1_000);
		fire(run1, 3, offer(560, 500, 0, 0, GrandExchangeOfferState.BUYING, 5));
		AsyncWriter.flush(2_000); // offer-state.json has to be on disk, like a real shutdown

		Thread.sleep(60); // the Windows clock ticks in ~15ms steps; force it forward

		// run 2: a fresh plugin object, a fresh session, the login re-fires the
		// slot already in its terminal state — the exact shape of "I logged out
		// to wait on my offers"
		final RuneAIPlugin run2 = newPlugin(gson, memory, svc, System.currentTimeMillis());
		call(run2, "loadOfferState");
		call(run2, "loadFlipBasis");
		call(run2, "loadTrader");
		set(run2, "lastLoginMs", System.currentTimeMillis());

		fire(run2, 3, offer(560, 500, 5, 2_500, GrandExchangeOfferState.BOUGHT, 5));

		final long[] basis = basisOf(run2, 560);
		assertNotNull("an offline fill must still open the basis", basis);
		assertEquals(5, basis[0]);
		assertEquals(2_500, basis[1]);
		// placed before this session started: money is booked, the session's
		// throughput counters are not inflated by it
		assertEquals(0L, get(run2, "unitsBought"));
		assertEquals(0, get(run2, "sessionFills"));
		// the slot is spent — a restart must not resume a finished offer
		assertNull(((Object[]) get(run2, "offerTracks"))[3]);

		Thread.sleep(60);

		// now the sell, placed and filled in THIS session, on the offline basis
		fire(run2, 3, offer(560, 600, 0, 0, GrandExchangeOfferState.SELLING, 5));
		fire(run2, 3, offer(560, 600, 5, 3_000, GrandExchangeOfferState.SOLD, 5));

		// 3,000 gross − 60 tax (12 x 5) − 2,500 offline cost = 440
		assertEquals(440L, get(run2, "flipRealized"));
		assertEquals(440L, get(run2, "lifetimeRealized"));
		assertEquals(5L, get(run2, "unitsSold"));
	}

	// ================= scenario C: a loss persists to lifetime =================

	@Test
	public void aLossIsBookedAndSurvivesARestart() throws Exception
	{
		final Gson gson = new Gson();
		final ItemMemory memory = new ItemMemory(gson);
		final FlipService svc = service(gson, memory);
		final RuneAIPlugin p = newPlugin(gson, memory, svc, System.currentTimeMillis() - 1_000);

		fire(p, 1, offer(2, 1_000, 0, 0, GrandExchangeOfferState.BUYING, 4));
		fire(p, 1, offer(2, 1_000, 4, 4_000, GrandExchangeOfferState.BOUGHT, 4));
		fire(p, 1, offer(2, 900, 0, 0, GrandExchangeOfferState.SELLING, 4));
		fire(p, 1, offer(2, 900, 4, 3_600, GrandExchangeOfferState.SOLD, 4));

		// 3,600 gross − 72 tax (18 x 4) − 4,000 cost = −472
		assertEquals(-472L, get(p, "flipRealized"));
		assertEquals(-472L, get(p, "lifetimeRealized"));

		// the regression that drifted the all-time number optimistic forever:
		// losses were never persisted. A fresh load has to see the −472.
		AsyncWriter.flush(2_000);
		final RuneAIPlugin fresh = newPlugin(gson, memory, svc, System.currentTimeMillis());
		call(fresh, "loadTrader");
		assertEquals(-472L, get(fresh, "lifetimeRealized"));
	}
}
