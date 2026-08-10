package com.runeai;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * In-game flip advice, shown only while the GE window is open — help lives
 * in the game window, the sidebar is for settings/status. Alt-drag to move.
 */
public class GeFlipOverlay extends Overlay
{
	private static final Color GOLD = new Color(255, 200, 0);
	private static final int W = 290;

	private final Client client;
	private final RuneAIConfig config;
	private final FlipService flips;

	private volatile int activeSlots, totalSlots = 3;
	private volatile long medBuySecs = -1, medSellSecs = -1;
	private volatile int sugFills, sugCancels;

	private volatile long flipGpHr;
	private volatile long realized;
	private volatile int buys, sells;
	private volatile long sessionMin;

	void setStats(int active, int total, long medBuy, long medSell, int fills, int cancels,
		long gpHr, long realizedPnl, int buyCount, int sellCount, long sessMin)
	{
		flipGpHr = gpHr;
		realized = realizedPnl;
		buys = buyCount;
		sells = sellCount;
		sessionMin = sessMin;
		activeSlots = active;
		totalSlots = total;
		medBuySecs = medBuy;
		medSellSecs = medSell;
		sugFills = fills;
		sugCancels = cancels;
	}

	@Inject
	GeFlipOverlay(Client client, RuneAIConfig config, FlipService flips)
	{
		this.client = client;
		this.config = config;
		this.flips = flips;
		setPosition(OverlayPosition.TOP_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showOverlays())
		{
			return null;
		}
		final Widget ge = client.getWidget(465, 0);
		if (ge == null || ge.isHidden())
		{
			return null;
		}

		// OFFER-SETUP COACH: the item currently in the setup screen gets
		// exact prices, qty, and total projected profit — any item, not just picks
		final int setupItem = client.getVarpValue(net.runelite.api.VarPlayer.CURRENT_GE_ITEM);
		if (setupItem > 0)
		{
			return renderOfferCoach(g, setupItem);
		}

		final List<FlipService.Flip> top = flips.getTopFlips();

		// open positions: every live offer with its exit plan
		final List<String[]> positions = new ArrayList<>();
		final net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (net.runelite.api.GrandExchangeOffer o : offers)
		{
			final net.runelite.api.GrandExchangeOfferState st = o.getState();
			if (o.getItemId() <= 0
				|| st == net.runelite.api.GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			final boolean buying = st == net.runelite.api.GrandExchangeOfferState.BUYING
				|| st == net.runelite.api.GrandExchangeOfferState.BOUGHT;
			final long[] q = flips.quoteFor(o.getItemId());
			final String head = String.format("%s %,d× %s @ %,d  (%d/%d)",
				buying ? "BUY" : "SELL", o.getTotalQuantity(),
				trunc(flips.nameFor(o.getItemId()), 16), o.getPrice(),
				o.getQuantitySold(), o.getTotalQuantity());
			String plan;
			if (buying && q != null)
			{
				final long sellAt = q[1];
				final long profit = (sellAt - FlipService.geTax((int) sellAt) - o.getPrice())
					* o.getTotalQuantity();
				plan = String.format("→ sell at %,d  =  %+,d gp when done", sellAt, profit);
			}
			else if (!buying)
			{
				final long proceeds = (long) (o.getPrice() - FlipService.geTax(o.getPrice()))
					* o.getTotalQuantity();
				plan = String.format("→ %,d gp after tax when done", proceeds);
			}
			else
			{
				plan = "→ no live quote";
			}
			positions.add(new String[]{head, plan, buying ? "b" : "s"});
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final int freeSlots = Math.max(0, totalSlots - activeSlots);
		final int rows = Math.min(Math.min(freeSlots == 0 ? 0 : Math.max(freeSlots, 1), 3),
			top.size());
		final int posH = positions.isEmpty() ? 0 : 20 + positions.size() * 34;
		final int h = 66 + posH + (rows > 0 ? rows * 36 : 0) + 40;

		g.setColor(new Color(12, 12, 18, 235));
		g.fillRoundRect(0, 0, W, h, 10, 10);
		g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 200));
		g.setStroke(new BasicStroke(1.5f));
		g.drawRoundRect(0, 0, W, h, 10, 10);

		g.setFont(g.getFont().deriveFont(Font.BOLD, 17f));
		g.setColor(GOLD);
		g.drawString("RuneAI · flips for YOUR budget", 10, 24);

		// pph telemetry: slot use + median fill times + suggestion record
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
		final boolean idle = activeSlots < totalSlots;
		g.setColor(idle ? new Color(255, 120, 100) : new Color(120, 220, 140));
		String stat = String.format("slots %d/%d%s", activeSlots, totalSlots,
			idle ? " — idle slots = lost gp/hr" : " ✓");
		g.drawString(stat, 10, 41);
		if (medBuySecs >= 0 || medSellSecs >= 0 || sugFills + sugCancels > 0)
		{
			g.setColor(Color.LIGHT_GRAY);
			g.drawString(String.format("fills: buy ~%ss · sell ~%ss",
				medBuySecs < 0 ? "?" : medBuySecs, medSellSecs < 0 ? "?" : medSellSecs), 10, 56);
		}

		int y = 74;

		// YOUR OFFERS first — the money you have in flight
		if (!positions.isEmpty())
		{
			g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
			g.setColor(new Color(140, 200, 255));
			g.drawString("YOUR OFFERS", 10, y);
			y += 17;
			for (String[] pos : positions)
			{
				g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
				g.setColor("b".equals(pos[2]) ? new Color(140, 200, 255) : new Color(255, 170, 120));
				g.drawString(pos[0], 10, y);
				g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
				g.setColor(new Color(120, 220, 140));
				g.drawString(pos[1], 16, y + 15);
				y += 34;
			}
			y += 4;
		}

		if (rows > 0 && top.isEmpty())
		{
			g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("fetching live prices…", 10, y);
		}
		for (int i = 0; i < rows; i++)
		{
			final FlipService.Flip f = top.get(i);
			g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
			g.setColor(Color.WHITE);
			g.drawString(trunc(f.getName(), 26), 10, y);
			g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
			g.setColor(GOLD);
			g.drawString(String.format("buy %,d → sell %,d  +%,d  ~%,.0f/hr",
				f.getBuyAt(), f.getSellAt(), f.getNet(), f.getUnitsHr() * 20), 10, y + 16);
			y += 36;
		}

		// SESSION stats footer — the numbers that matter
		g.setColor(new Color(255, 255, 255, 40));
		g.drawLine(10, y - 4, W - 10, y - 4);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
		g.setColor(realized >= 0 ? new Color(120, 220, 140) : new Color(255, 100, 100));
		g.drawString(String.format("SESSION %+,d gp · %,d gp/h", realized, flipGpHr), 10, y + 12);
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
		g.setColor(Color.LIGHT_GRAY);
		g.drawString(String.format("%d buys · %d sells · calls %d✓/%d✗ · %dm",
			buys, sells, sugFills, sugCancels, sessionMin), 10, y + 27);
		return new Dimension(W, h);
	}

	private Dimension renderOfferCoach(Graphics2D g, int itemId)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final long[] q = flips.quoteFor(itemId);
		final int h = 150;
		g.setColor(new Color(12, 12, 18, 235));
		g.fillRoundRect(0, 0, W, h, 10, 10);
		g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 200));
		g.setStroke(new BasicStroke(1.5f));
		g.drawRoundRect(0, 0, W, h, 10, 10);

		g.setFont(g.getFont().deriveFont(Font.BOLD, 17f));
		g.setColor(GOLD);
		g.drawString(trunc(flips.nameFor(itemId), 28), 10, 24);

		if (q == null)
		{
			g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("no live data for this item", 10, 44);
			return new Dimension(W, h);
		}
		final long buyAt = q[0], sellAt = q[1], volHr = q[2];
		final int net = (int) (sellAt - FlipService.geTax((int) sellAt) - buyAt);
		final int limit = flips.limitFor(itemId);
		final long budget = flips.getBudget();
		long qty = Math.max(1, Math.min(limit, volHr / 10));
		if (budget > 0)
		{
			qty = Math.min(qty, Math.max(1, budget / Math.max(1, buyAt)));
		}
		final long total = net * qty;

		g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
		g.setColor(Color.WHITE);
		g.drawString(String.format("BUY at  %,d      SELL at  %,d", buyAt, sellAt), 10, 50);
		g.setColor(net > 0 ? new Color(120, 220, 140) : new Color(255, 120, 100));
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 14f));
		g.drawString(String.format("net %+,d each after tax", net), 10, 74);
		g.setColor(Color.LIGHT_GRAY);
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
		g.drawString(String.format("~%,d traded/hr · buy limit %,d", volHr, limit), 10, 96);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
		g.setColor(GOLD);
		g.drawString(String.format("suggested qty %,d  →  total %+,d gp", qty, total), 10, 122);
		return new Dimension(W, h);
	}

	private static String trunc(String s, int n)
	{
		return s.length() <= n ? s : s.substring(0, n - 1) + "…";
	}
}
