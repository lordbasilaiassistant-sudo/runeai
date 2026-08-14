package com.runeai;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
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
 * The GE window IS the dashboard. Nothing floats beside the interface any
 * more — advice is drawn into the real estate the interface already spends:
 *
 * <ul>
 *   <li><b>Empty slots</b> become pick tiles: the next quick-lane flip or
 *       patience order to park, name in the slot's title strip and prices in
 *       its bottom strip. The buy/sell buttons in the middle stay untouched
 *       and clickable.</li>
 *   <li><b>The footer strip</b> under the slot grid carries the session line:
 *       realized P&L, gp/h, all-time, fill medians, trader level.</li>
 *   <li><b>The offer-setup screen</b> gets the coach drawn over the item
 *       description panel — the space the interface spends re-telling you what
 *       a mushroom is — never over the quantity/price buttons.</li>
 * </ul>
 *
 * Occupied slots belong to {@link GeSlotStampOverlay}'s verdict stamps.
 */
public class GeFlipOverlay extends Overlay
{
	private static final Color GOLD = new Color(255, 200, 0);
	private static final Color TRAP = new Color(190, 140, 255);
	private static final Color GAIN = new Color(120, 220, 140);
	private static final Color LOSS = new Color(255, 100, 100);
	private static final Color BG = new Color(12, 12, 18, 225);

	/** GE interface group and its well-known children. */
	private static final int GE_GROUP = 465;
	private static final int GE_FIRST_SLOT_CHILD = 7;
	/** The setup screen's item-description panel (ComponentID.GRAND_EXCHANGE_OFFER_DESCRIPTION). */
	private static final int GE_SETUP_DESC_CHILD = 27;

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
	private volatile java.util.List<TrapBoard.Pick> traps = java.util.List.of();

	void setPendingSells(java.util.List<String> p)
	{
		pendingSells = p;
	}

	private volatile boolean overnight;

	void setTraps(java.util.List<TrapBoard.Pick> t, boolean headingOffline)
	{
		traps = t;
		overnight = headingOffline;
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
		// DYNAMIC: everything here is anchored to real GE widget bounds
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showOverlays())
		{
			return null;
		}
		final Widget ge = client.getWidget(GE_GROUP, 0);
		if (ge == null || ge.isHidden())
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final int setupItem = client.getVarpValue(net.runelite.api.VarPlayer.CURRENT_GE_ITEM);
		if (setupItem > 0)
		{
			renderOfferCoach(g, setupItem);
			return null;
		}
		// item-search open = THE decision moment: big clear picks over the setup area
		final Widget searchBox = client.getWidget(162, 42);
		if (searchBox != null && !searchBox.isHidden())
		{
			renderPicker(g);
			return null;
		}
		renderDash(g);
		return null;
	}

	// ================= the main screen: slots as dashboard =================

	private void renderDash(Graphics2D g)
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		final java.util.Set<Integer> activeItems = new java.util.HashSet<>();
		for (GrandExchangeOffer o : offers)
		{
			if (o != null && o.getItemId() > 0
				&& o.getState() != GrandExchangeOfferState.EMPTY)
			{
				activeItems.add(o.getItemId());
			}
		}

		// the queue of cards to deal onto empty slots: patience orders first
		// (the plugin already budgeted how many), then quick-lane picks
		final List<TrapBoard.Pick> trapCards = new ArrayList<>(traps);
		final List<FlipService.Flip> quickCards = new ArrayList<>();
		for (FlipService.Flip f : flips.getTopFlips())
		{
			if (!activeItems.contains(f.getItemId()))
			{
				quickCards.add(f);
			}
		}

		Rectangle grid = null;
		for (int i = 0; i < 8; i++)
		{
			final Widget slot = client.getWidget(GE_GROUP, GE_FIRST_SLOT_CHILD + i);
			if (slot == null || slot.isHidden() || slot.getBounds() == null)
			{
				continue;
			}
			final Rectangle b = slot.getBounds();
			grid = grid == null ? new Rectangle(b) : grid.union(b);
			if (i >= totalSlots)
			{
				continue; // a locked members slot is not a place to park advice
			}
			final GrandExchangeOffer o = i < offers.length ? offers[i] : null;
			final boolean empty = o == null || o.getItemId() <= 0
				|| o.getState() == GrandExchangeOfferState.EMPTY;
			if (!empty)
			{
				continue; // occupied slots carry the verdict stamps
			}
			if (!trapCards.isEmpty())
			{
				final TrapBoard.Pick p = trapCards.remove(0);
				tile(g, b, p.getName(), TRAP,
					String.format("%s→%s · %.0fx", fmtK(p.getBuyAt()), fmtK(p.getListAt()), p.getPayoffX()),
					overnight ? "park & log off" : "patience order");
			}
			else if (!quickCards.isEmpty())
			{
				final FlipService.Flip f = quickCards.remove(0);
				tile(g, b, f.getName(), GOLD,
					String.format("%,d→%,d", f.getBuyAt(), f.getSellAt()),
					f.isInsta()
						? String.format("+%d ⚡ now", f.getNet())
						: String.format("+%d q~%ds", f.getNet(), f.getCycleSecs()));
			}
		}

		if (grid == null)
		{
			return;
		}
		// footer: the session line, pinned under the slot grid inside the window
		final int fy = grid.y + grid.height + 2;
		g.setColor(BG);
		g.fillRoundRect(grid.x, fy, grid.width, pendingSells.isEmpty() ? 17 : 32, 6, 6);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 12));
		g.setColor(realized >= 0 ? GAIN : LOSS);
		final String left = String.format("%+,d · %s/h · life %s", realized, fmtK(flipGpHr), fmtK(lifetime));
		g.drawString(left, grid.x + 6, fy + 13);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 11));
		g.setColor(Color.LIGHT_GRAY);
		final StringBuilder right = new StringBuilder();
		right.append(buys).append("b·").append(sells).append("s");
		if (medBuySecs >= 0 || medSellSecs >= 0)
		{
			right.append("  ");
			if (medBuySecs >= 0)
			{
				right.append("b~").append(medBuySecs).append("s ");
			}
			if (medSellSecs >= 0)
			{
				right.append("s~").append(medSellSecs).append("s");
			}
		}
		right.append("  T").append(traderLvl).append(" ").append((int) (traderPct * 100)).append("%");
		final String rs = right.toString();
		g.drawString(rs, grid.x + grid.width - 6 - g.getFontMetrics().stringWidth(rs), fy + 13);
		if (!pendingSells.isEmpty())
		{
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 11));
			g.setColor(new Color(255, 170, 60));
			g.drawString(fit(g, "SELL FIRST: " + String.join(" · ", pendingSells), grid.width - 12),
				grid.x + 6, fy + 27);
		}
	}

	/** One empty-slot card: name over the title strip, numbers in the bottom strip. */
	private void tile(Graphics2D g, Rectangle b, String name, Color accent, String prices, String note)
	{
		// title strip — sits over the interface's own "Empty" label, nothing clickable there
		g.setColor(BG);
		g.fillRect(b.x + 2, b.y + 2, b.width - 4, 16);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 11));
		g.setColor(accent);
		g.drawString(fit(g, name, b.width - 10), b.x + 5, b.y + 14);
		// bottom strip — under the buy/sell buttons, over the slot's empty base
		g.setColor(BG);
		g.fillRect(b.x + 2, b.y + b.height - 32, b.width - 4, 30);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 11));
		g.setColor(Color.WHITE);
		g.drawString(fit(g, prices, b.width - 10), b.x + 5, b.y + b.height - 20);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 10));
		g.setColor(accent);
		g.drawString(fit(g, note, b.width - 10), b.x + 5, b.y + b.height - 7);
	}

	// ================= the search screen: what to type =================

	private void renderPicker(Graphics2D g)
	{
		final Rectangle a = anchorRect();
		final List<FlipService.Flip> top = flips.getTopFlips();
		final int n = Math.min(3, top.size());
		final int w = Math.min(340, a.width);
		final int h = 34 + Math.max(1, n) * 34 + 6;
		final int x = a.x + (a.width - w) / 2;
		final int y = a.y;
		g.setColor(new Color(12, 12, 18, 240));
		g.fillRoundRect(x, y, w, h, 10, 10);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(1.6f));
		g.drawRoundRect(x, y, w, h, 10, 10);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 14));
		g.drawString("TYPE ONE OF THESE:", x + 10, y + 22);
		int yy = y + 42;
		if (n == 0)
		{
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 13));
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("fetching prices…", x + 10, yy);
		}
		for (int i = 0; i < n; i++)
		{
			final FlipService.Flip f = top.get(i);
			long qty = Math.max(1, Math.min(flips.remainingLimit(f.getItemId()),
				(long) (f.getUnitsHr())));
			final long budget = flips.getBudget();
			if (budget > 0)
			{
				qty = Math.min(qty, Math.max(1, budget / Math.max(1, f.getBuyAt())));
			}
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.BOLD, 14));
			g.setColor(Color.WHITE);
			g.drawString(fit(g, f.getName(), w - 20), x + 10, yy);
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, Font.PLAIN, 12));
			g.setColor(GOLD);
			g.drawString(fit(g, String.format("buy %,d · qty %,d · +%d ea · sell %,d%s",
				f.getBuyAt(), qty, f.getNet(), f.getSellAt(),
				f.isInsta() ? " ⚡now" : " q~" + f.getCycleSecs() + "s"), w - 20), x + 10, yy + 15);
			yy += 34;
		}
	}

	/** Where setup-screen panels anchor: the offer container, else the window. */
	private Rectangle anchorRect()
	{
		final Widget cont = client.getWidget(GE_GROUP, 26);
		if (cont != null && !cont.isHidden() && cont.getBounds() != null
			&& cont.getBounds().width > 50)
		{
			return cont.getBounds();
		}
		final Widget ge = client.getWidget(GE_GROUP, 0);
		return ge != null && ge.getBounds() != null ? ge.getBounds() : new Rectangle(8, 8, 400, 300);
	}

	// ================= the setup screen: the offer coach =================

	/**
	 * Drawn over the interface's item-description panel — the space the game
	 * spends telling you a mushroom comes from a swamp. Never over the
	 * quantity/price controls, which stay fully visible and clickable.
	 *
	 * <p>The one rule with teeth: a suggestion is only printed when it MAKES
	 * money. "suggested qty 3,073 → total −15,365" is not a suggestion, it is a
	 * coached loss, and the screen that did that gets NO MARGIN in red instead.
	 */
	private void renderOfferCoach(Graphics2D g, int itemId)
	{
		final Widget desc = client.getWidget(GE_GROUP, GE_SETUP_DESC_CHILD);
		final Rectangle box = desc != null && !desc.isHidden() && desc.getBounds() != null
			&& desc.getBounds().width > 50
			? desc.getBounds()
			: new Rectangle(anchorRect().x + 8, anchorRect().y + 30, 360, 86);
		final int x = box.x;
		final int w = box.width;
		int y = box.y;
		final int h = Math.max(box.height, 72);

		g.setColor(new Color(12, 12, 18, 242));
		g.fillRoundRect(x, y, w, h, 8, 8);
		g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 180));
		g.setStroke(new BasicStroke(1.2f));
		g.drawRoundRect(x, y, w, h, 8, 8);

		final long[] q = flips.quoteFor(itemId);
		if (q == null)
		{
			line(g, x, y + 18, w, "no live data for this item", Color.LIGHT_GRAY, 12, false);
			return;
		}
		final long buyAt = q[0], sellAt = q[1], volHr = q[2];
		final long[] book = flips.bookFor(itemId);
		final long iNet = book != null ? FlipService.instaNet(book[0], book[1]) : -1;
		final int limit = flips.limitFor(itemId);
		final long budget = flips.getBudget();
		final boolean selling = client.getVarbitValue(4397) == 1;

		if (config.trapGuard() && flips.isTrap(itemId))
		{
			line(g, x, y + 17, w, "THIN BOOK — no real spread", TRAP, 14, true);
			if (book != null)
			{
				line(g, x, y + 34, w, String.format("sellers accept ~%,d · one buyer paid %,d", book[0], book[1]),
					Color.LIGHT_GRAY, 12, false);
			}
			line(g, x, y + 50, w, String.format("~%,d traded/hr — nobody on the other side", volHr),
				Color.LIGHT_GRAY, 12, false);
			line(g, x, y + 67, w, "patience order only, never a flip", GOLD, 13, true);
			return;
		}

		final int net = (int) (sellAt - FlipService.geTax((int) sellAt) - buyAt);
		if (selling)
		{
			coachSell(g, x, y, w, itemId, sellAt, volHr);
			return;
		}

		// -------- buy setup --------
		long unsold = heldOf(itemId) + onSellOffers(itemId);
		final long remaining = flips.remainingLimit(itemId);
		final double ofi = flips.ofi(itemId);
		final String flowNote = ofi != 0
			? String.format(" · buyers %d%%", Math.round(50 + 50 * ofi)) : "";
		if (remaining <= 0)
		{
			// the game enforces this silently — an offer past the limit just sits
			line(g, x, y + 17, w, "BUY LIMIT USED — 4h window is spent", LOSS, 14, true);
			line(g, x, y + 34, w, String.format("limit %,d · every unit bought in the last 4h counts", limit),
				Color.LIGHT_GRAY, 12, false);
			line(g, x, y + 51, w, "an offer placed now will sit — pick another item", GOLD, 13, true);
		}
		else if (iNet > 0)
		{
			// the genuinely quick play: cross the spread, both sides fill now
			line(g, x, y + 17, w, String.format("⚡ BUY at %,d · SELL at %,d — both fill now", book[1], book[0]), GOLD, 14, true);
			long qty = Math.max(1, Math.min(Math.min(limit, remaining), volHr / 10));
			if (budget > 0)
			{
				qty = Math.min(qty, Math.max(1, budget / Math.max(1, book[1])));
			}
			line(g, x, y + 34, w, String.format("+%d each after tax · ~%,d traded/hr · limit left %,d%s",
				iNet, volHr, remaining, flowNote), GAIN, 12, false);
			line(g, x, y + 51, w, String.format("qty %,d → +%,d gp", qty, iNet * qty), GAIN, 13, true);
		}
		else if (net > 0)
		{
			line(g, x, y + 17, w, String.format("BUY at %,d · SELL at %,d  (queue prices)", buyAt, sellAt), Color.WHITE, 14, true);
			long qty = Math.max(1, Math.min(Math.min(limit, remaining), volHr / 10));
			if (budget > 0)
			{
				qty = Math.min(qty, Math.max(1, budget / Math.max(1, buyAt)));
			}
			line(g, x, y + 34, w, String.format("+%d each after tax · ~%,d traded/hr · limit left %,d%s",
				net, volHr, remaining, flowNote), GAIN, 12, false);
			line(g, x, y + 51, w, String.format("qty %,d → +%,d gp · expect a wait", qty, (long) net * qty), GOLD, 13, true);
		}
		else
		{
			// NOTHING here makes money right now — say that, suggest nothing
			final long bestNet = book != null ? Math.max(net, iNet) : net;
			line(g, x, y + 17, w, "NO MARGIN — this item pays nothing right now", LOSS, 14, true);
			line(g, x, y + 34, w, String.format("book %,d / %,d · after tax you LOSE %,d each", buyAt, sellAt, Math.max(1, -bestNet)),
				Color.LIGHT_GRAY, 12, false);
			line(g, x, y + 51, w, "skip it — the quick lane has better", GOLD, 13, true);
		}
		if (unsold > 0)
		{
			line(g, x, y + h - 6, w, String.format("⚠ %,d unsold already — sell through first", unsold), LOSS, 12, true);
		}
	}

	private void coachSell(Graphics2D g, int x, int y, int w, int itemId, long sellAt, long volHr)
	{
		final long avg = flips.avgCost(itemId);
		final long breakeven = avg >= 0 ? FlipService.breakevenSell(avg) : -1;
		final long held = Math.max(1, heldOf(itemId));
		final long coached = breakeven > 0 ? Math.max(sellAt, breakeven) : sellAt;
		line(g, x, y + 17, w, String.format("SELL at %,d", coached), Color.WHITE, 14, true);
		if (avg >= 0)
		{
			if (sellAt < breakeven)
			{
				line(g, x, y + 34, w, String.format("book %,d is UNDER your cost ~%,d (−%,d ea)", sellAt, avg,
					avg - (sellAt - FlipService.geTax((int) sellAt))), LOSS, 12, true);
				line(g, x, y + 51, w, String.format("ask %,d to exit clean — or hold", breakeven), GOLD, 13, true);
			}
			else
			{
				line(g, x, y + 34, w, String.format("paid ~%,d · breakeven %,d ✓", avg, breakeven), GAIN, 12, false);
				line(g, x, y + 51, w, String.format("sell ALL %,d → %,d gp after tax", held,
					(coached - FlipService.geTax((int) coached)) * held), GOLD, 13, true);
			}
		}
		else
		{
			line(g, x, y + 34, w, String.format("~%,d traded/hr", volHr), Color.LIGHT_GRAY, 12, false);
			line(g, x, y + 51, w, String.format("sell ALL %,d → %,d gp after tax", held,
				(coached - FlipService.geTax((int) coached)) * held), GOLD, 13, true);
		}
	}

	private void line(Graphics2D g, int x, int y, int w, String text, Color c, int size, boolean bold)
	{
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
		g.setColor(c);
		g.drawString(fit(g, text, w - 16), x + 8, y);
	}

	private long heldOf(int itemId)
	{
		long held = 0;
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it != null && it.getId() > 0 && itemManager.canonicalize(it.getId()) == itemId)
				{
					held += it.getQuantity();
				}
			}
		}
		return held;
	}

	private long onSellOffers(int itemId)
	{
		long unsold = 0;
		for (GrandExchangeOffer of : client.getGrandExchangeOffers())
		{
			if (of != null && of.getItemId() == itemId
				&& of.getState() == GrandExchangeOfferState.SELLING)
			{
				unsold += of.getTotalQuantity() - of.getQuantitySold();
			}
		}
		return unsold;
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
}
