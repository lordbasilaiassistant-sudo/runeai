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
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemContainer;
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
	private static final int W = 304;

	private final Client client;
	private final RuneAIConfig config;
	private final FlipService flips;
	private final net.runelite.client.game.ItemManager itemManager;

	private volatile int activeSlots, totalSlots = 3;
	private volatile long medBuySecs = -1, medSellSecs = -1;
	private volatile int sugFills, sugCancels;

	private volatile long flipGpHr;
	private volatile long realized;
	private volatile int buys, sells;
	private volatile long sessionMin;
	private volatile int traderLvl = 1;
	private volatile double traderPct;

	void setTrader(int lvl, double pct)
	{
		traderLvl = lvl;
		traderPct = pct;
	}

	private volatile long lifetime;
	private volatile java.util.List<String> pendingSells = java.util.List.of();

	void setPendingSells(java.util.List<String> p)
	{
		pendingSells = p;
	}

	void setStats(int active, int total, long medBuy, long medSell, int fills, int cancels,
		long gpHr, long realizedPnl, long lifetimePnl, int buyCount, int sellCount, long sessMin)
	{
		lifetime = lifetimePnl;
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
	GeFlipOverlay(Client client, RuneAIConfig config, FlipService flips,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.flips = flips;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT); // default where it does not cover the GE window
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
		final int setupItem = client.getVarpValue(net.runelite.api.VarPlayer.CURRENT_GE_ITEM);
		if (setupItem > 0)
		{
			return renderOfferCoach(g, setupItem);
		}
		// item-search open = THE decision moment: big clear picks
		final Widget searchBox = client.getWidget(162, 42);
		if (searchBox != null && !searchBox.isHidden())
		{
			return renderPicker(g);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// compact one-liners for every live offer
		final List<String[]> pos = new ArrayList<>();
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		final java.util.Set<Integer> activeItems = new java.util.HashSet<>();
		for (GrandExchangeOffer o : offers)
		{
			final net.runelite.api.GrandExchangeOfferState st = o == null ? null : o.getState();
			if (o == null || o.getItemId() <= 0 || st == net.runelite.api.GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			activeItems.add(o.getItemId());
			final boolean buying = st == net.runelite.api.GrandExchangeOfferState.BUYING
				|| st == net.runelite.api.GrandExchangeOfferState.BOUGHT;
			final long[] q = flips.quoteFor(o.getItemId());
			String plan = "";
			if (buying && q != null)
			{
				// a trap item's "profit" is the whale print times quantity — fiction
				plan = config.trapGuard() && flips.isTrap(o.getItemId())
					? "→thin book"
					: "→+" + fmtK((q[1] - FlipService.geTax((int) q[1]) - o.getPrice()) * o.getTotalQuantity());
			}
			else if (!buying)
			{
				plan = "→" + fmtK((long) (o.getPrice() - FlipService.geTax(o.getPrice())) * o.getTotalQuantity());
			}
			pos.add(new String[]{buying ? "BUY" : "SELL", flips.nameFor(o.getItemId()),
				String.format("%d/%d  %s", o.getQuantitySold(), o.getTotalQuantity(), plan),
				buying ? "b" : "s"});
		}

		final List<FlipService.Flip> top = new ArrayList<>();
		for (FlipService.Flip f : flips.getTopFlips())
		{
			if (!activeItems.contains(f.getItemId()))
			{
				top.add(f);
			}
		}
		final int freeSlots = Math.max(0, totalSlots - activeSlots);
		final int rows = freeSlots == 0 ? 0 : Math.min(3, top.size());
		final int shown = Math.min(pos.size(), 6);

		final int h = 48 + (pendingSells.isEmpty() ? 0 : pendingSells.size() * 16)
			+ (shown > 0 ? 15 + shown * 18 : 0)
			+ (rows > 0 ? 15 + rows * 18 : 0) + 40;

		g.setColor(new Color(12, 12, 18, 235));
		g.fillRoundRect(0, 0, W, h, 10, 10);
		g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 200));
		g.setStroke(new BasicStroke(1.2f));
		g.drawRoundRect(0, 0, W, h, 10, 10);

		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 15));
		g.setColor(GOLD);
		g.drawString("RuneAI flips", 8, 18);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 12));
		final boolean idle = activeSlots < totalSlots;
		g.setColor(idle ? new Color(255, 120, 100) : new Color(120, 220, 140));
		final String stat = String.format("slots %d/%d%s · b~%ss s~%ss", activeSlots, totalSlots,
			idle ? "!" : "✓", medBuySecs < 0 ? "?" : medBuySecs, medSellSecs < 0 ? "?" : medSellSecs);
		g.drawString(stat, W - 8 - g.getFontMetrics().stringWidth(stat), 18);

		int y = 36;
		if (!pendingSells.isEmpty())
		{
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 12));
			g.setColor(new Color(255, 170, 60));
			for (String ps : pendingSells)
			{
				g.drawString("SELL FIRST: " + trunc(ps, 30), 8, y);
				y += 16;
			}
		}
		if (shown > 0)
		{
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 11));
			g.setColor(new Color(140, 200, 255));
			g.drawString("OFFERS", 8, y);
			y += 15;
			for (int i = 0; i < shown; i++)
			{
				final String[] ps = pos.get(i);
				g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 13));
				final int rightW = g.getFontMetrics().stringWidth(ps[2]);
				g.setColor(new Color(120, 220, 140));
				g.drawString(ps[2], W - 8 - rightW, y);
				g.setColor("b".equals(ps[3]) ? new Color(140, 200, 255) : new Color(255, 170, 120));
				// the name gets EVERY pixel the numbers don't need
				g.drawString(ps[0] + " " + fit(g, ps[1], W - 22 - rightW
					- g.getFontMetrics().stringWidth(ps[0] + " ")), 8, y);
				y += 18;
			}
		}
		if (rows > 0)
		{
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 11));
			g.setColor(GOLD);
			g.drawString("SUGGESTED", 8, y);
			y += 15;
			for (int i = 0; i < rows; i++)
			{
				final FlipService.Flip f = top.get(i);
				g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 12));
				final String rest = String.format("%,d  +%d/ea", f.getBuyAt(), f.getNet());
				final int restW = g.getFontMetrics().stringWidth(rest);
				g.setColor(GOLD);
				g.drawString(rest, W - 8 - restW, y);
				g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 13));
				g.setColor(Color.WHITE);
				g.drawString(fit(g, f.getName(), W - 22 - restW), 8, y);
				y += 18;
			}
		}

		g.setColor(new Color(255, 255, 255, 40));
		g.drawLine(8, y - 4, W - 8, y - 4);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 13));
		g.setColor(realized >= 0 ? new Color(120, 220, 140) : new Color(255, 100, 100));
		g.drawString(String.format("%+,d · %s/h · life %s", realized, fmtK(flipGpHr), fmtK(lifetime)), 8, y + 11);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 12));
		g.setColor(Color.LIGHT_GRAY);
		g.drawString(String.format("%db · %ds · %d✓/%d✗ · %dm · T%d %.0f%%",
			buys, sells, sugFills, sugCancels, sessionMin, traderLvl, traderPct * 100), 8, y + 26);
		return new Dimension(W, h);
	}

	private Dimension renderPicker(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final List<FlipService.Flip> top = flips.getTopFlips();
		final int n = Math.min(3, top.size());
		final int h = 40 + Math.max(1, n) * 40 + 8;
		g.setColor(new Color(12, 12, 18, 240));
		g.fillRoundRect(0, 0, W, h, 10, 10);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(1.6f));
		g.drawRoundRect(0, 0, W, h, 10, 10);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 15));
		g.drawString("TYPE ONE OF THESE:", 10, 24);
		int y = 46;
		if (n == 0)
		{
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 13));
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("fetching prices…", 10, y);
		}
		for (int i = 0; i < n; i++)
		{
			final FlipService.Flip f = top.get(i);
			long qty = Math.max(1, Math.min(flips.limitFor(f.getItemId()),
				(long) (f.getUnitsHr())));
			final long budget = flips.getBudget();
			if (budget > 0)
			{
				qty = Math.min(qty, Math.max(1, budget / Math.max(1, f.getBuyAt())));
			}
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 15));
			g.setColor(Color.WHITE);
			g.drawString(f.getName(), 10, y);
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 13));
			g.setColor(GOLD);
			g.drawString(String.format("buy %,d · qty %,d · +%d each · sell %,d",
				f.getBuyAt(), qty, f.getNet(), f.getSellAt()), 10, y + 16);
			y += 40;
		}
		return new Dimension(W, h);
	}

	/** Fit a name into a pixel budget: abbreviate first, ellipsize last. */
	private static String fit(Graphics2D g, String name, int maxPx)
	{
		if (g.getFontMetrics().stringWidth(name) <= maxPx)
		{
			return name;
		}
		String a = name.replace("teleport", "tele").replace("Teleport", "Tele")
			.replace(" potion", " pot").replace("Extended ", "Ext ")
			.replace("Haemostatic", "Haemo").replace(" dressing", " drs")
			.replace("Superior ", "Sup ").replace(" tablet", " tab");
		if (g.getFontMetrics().stringWidth(a) <= maxPx)
		{
			return a;
		}
		while (a.length() > 3 && g.getFontMetrics().stringWidth(a + "…") > maxPx)
		{
			a = a.substring(0, a.length() - 1);
		}
		return a + "…";
	}

	private static String fmtK(long v)
	{
		final long a = Math.abs(v);
		if (a >= 1_000_000)
		{
			return String.format("%.1fM", v / 1_000_000.0);
		}
		if (a >= 1_000)
		{
			return String.format("%.1fk", v / 1_000.0);
		}
		return String.valueOf(v);
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

		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 16));
		g.setColor(GOLD);
		g.drawString(trunc(flips.nameFor(itemId), 28), 10, 24);

		if (q == null)
		{
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 12));
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("no live data for this item", 10, 44);
			return new Dimension(W, h);
		}
		final long buyAt = q[0], sellAt = q[1], volHr = q[2];
		if (config.trapGuard() && flips.isTrap(itemId))
		{
			// The spread here is one whale's print against an empty book. Showing a
			// "margin" would be inventing a counterparty that does not exist.
			final long[] book = flips.bookFor(itemId);
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 15));
			g.setColor(new Color(190, 140, 255));
			g.drawString("THIN BOOK — no real spread", 10, 50);
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 13));
			g.setColor(Color.LIGHT_GRAY);
			if (book != null)
			{
				g.drawString(String.format("sellers accept ~%,d · one buyer paid %,d", book[0], book[1]), 10, 74);
			}
			g.drawString(String.format("~%,d traded/hr — nobody is on the other side", volHr), 10, 96);
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 15));
			g.setColor(GOLD);
			g.drawString("patience order only, never a flip", 10, 122);
			return new Dimension(W, h);
		}
		final int net = (int) (sellAt - FlipService.geTax((int) sellAt) - buyAt);
		final int limit = flips.limitFor(itemId);
		final long budget = flips.getBudget();

		// GE varbit 4397: 0 = buy setup, 1 = sell setup — different advice entirely
		final boolean selling = client.getVarbitValue(4397) == 1;
		long qty;
		long total;
		if (!selling)
		{
			// allocation guard: buying MORE of something you haven't sold through
			// concentrates capital in an unproven exit — flag it loudly
			long unsold = 0;
			final ItemContainer inv2 = client.getItemContainer(InventoryID.INVENTORY);
			if (inv2 != null)
			{
				for (Item it : inv2.getItems())
				{
					if (it != null && it.getId() > 0 && itemManager.canonicalize(it.getId()) == itemId)
					{
						unsold += it.getQuantity();
					}
				}
			}
			for (GrandExchangeOffer of : client.getGrandExchangeOffers())
			{
				if (of != null && of.getItemId() == itemId
					&& (of.getState() == GrandExchangeOfferState.SELLING))
				{
					unsold += of.getTotalQuantity() - of.getQuantitySold();
				}
			}
			if (unsold > 0)
			{
				g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 13));
				g.setColor(new Color(255, 100, 100));
				g.drawString(String.format("⚠ %,d unsold already — sell through first", unsold), 10, 138);
			}
		}
		if (selling)
		{
			// selling: dump the full stack you hold at the undercut price
			long held = 0;
			final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				for (Item it : inv.getItems())
				{
					if (it != null && it.getId() > 0
						&& itemManager.canonicalize(it.getId()) == itemId)
					{
						held += it.getQuantity();
					}
				}
			}
			qty = Math.max(1, held);
			total = (sellAt - FlipService.geTax((int) sellAt)) * qty;
		}
		else
		{
			qty = Math.max(1, Math.min(limit, volHr / 10));
			if (budget > 0)
			{
				qty = Math.min(qty, Math.max(1, budget / Math.max(1, buyAt)));
			}
			total = net * qty;
		}

		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 15));
		g.setColor(Color.WHITE);
		g.drawString(String.format("BUY at  %,d      SELL at  %,d", buyAt, sellAt), 10, 50);
		g.setColor(net > 0 ? new Color(120, 220, 140) : new Color(255, 120, 100));
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 13));
		g.drawString(String.format("net %+,d each after tax", net), 10, 74);
		g.setColor(Color.LIGHT_GRAY);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 13));
		g.drawString(String.format("~%,d traded/hr · buy limit %,d", volHr, limit), 10, 96);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 15));
		g.setColor(GOLD);
		g.drawString(selling
			? String.format("sell ALL %,d  →  %,d gp after tax", qty, total)
			: String.format("suggested qty %,d  →  total %+,d gp", qty, total), 10, 122);
		return new Dimension(W, h);
	}

	private static String trunc(String s, int n)
	{
		return s.length() <= n ? s : s.substring(0, n - 1) + "…";
	}
}
