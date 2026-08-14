package com.runeai;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Verdict stamps ON the GE slot boxes: KEEP / CANCEL & reprice / ABORT.
 * Rule-based v1 (live-quote comparison) — every verdict is logged, and the
 * player's response to it is the training data for the learned policy.
 *
 * <p>Two short lines, never wider than the slot they grade. A stamp that
 * bleeds into the neighbouring slot is advice about the wrong offer.
 */
public class GeSlotStampOverlay extends Overlay
{
	private static final Color KEEP = new Color(60, 200, 110);
	private static final Color MOVE = new Color(255, 170, 60);
	private static final Color ABORT = new Color(255, 80, 80);
	private static final Color TRAP = new Color(190, 140, 255);

	private final Client client;
	private final RuneAIConfig config;
	private final FlipService flips;
	private final net.runelite.client.game.ItemManager itemManager;

	private volatile long[] offerStarts = new long[8];   // last fill activity
	private volatile long[] offerPlaced = new long[8];   // wall-clock placement (incl. offline)

	void setOfferStarts(long[] lastActivity, long[] placed)
	{
		offerStarts = lastActivity;
		offerPlaced = placed;
	}

	@Inject
	GeSlotStampOverlay(Client client, RuneAIConfig config, FlipService flips,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.flips = flips;
		this.itemManager = itemManager;
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
		final Widget ge = client.getWidget(465, 0);
		if (ge == null || ge.isHidden())
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int i = 0; i < offers.length; i++)
		{
			final GrandExchangeOffer o = offers[i];
			if (o == null || o.getItemId() <= 0 || o.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			final Widget slot = client.getWidget(465, 7 + i);
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			final long[] q = flips.quoteFor(o.getItemId());
			if (q == null)
			{
				continue;
			}
			final long low = q[0] - 1, high = q[1] + 1;
			String l1;
			String l2 = null;
			Color c;
			final boolean buying = o.getState() == GrandExchangeOfferState.BUYING
				|| o.getState() == GrandExchangeOfferState.BOUGHT;

			// a stalled offer at the quote isn't clearing — the reprice must step
			// DEEPER into the spread, never re-suggest the same number
			final long spreadStep = repriceStep(low, high);
			if (config.trapGuard() && flips.isTrap(o.getItemId()))
			{
				// One-sided book: the only recent high print is a whale overpaying
				// into an empty market. Every reprice target derived from it is
				// fiction, so quote no target at all.
				l1 = "THIN BOOK";
				l2 = buying ? "worth ~" + fmt(low) + " · don't chase" : "patience order · hold";
				c = TRAP;
			}
			else if (buying)
			{
				final long margin = (q[1] - FlipService.geTax((int) q[1])) - o.getPrice();
				// most a buy can pay and still net 1gp after tax on the sell side
				final long maxBuy = high - FlipService.geTax((int) high) - 1;
				final long stepUp = Math.min(o.getPrice() + spreadStep, maxBuy);
				if (margin <= 0)
				{
					l1 = "ABORT";
					l2 = "margin gone";
					c = ABORT;
				}
				else if (o.getPrice() < low)
				{
					l1 = "CANCEL";
					l2 = "rebuy " + fmt(Math.max(low + 1, stepUp));
					c = MOVE;
				}
				else if (isDead(i, o))
				{
					l1 = "DEAD " + placedMin(i) + "m";
					l2 = stepUp > o.getPrice() ? "rebuy " + fmt(stepUp) : "margin thin · skip";
					c = ABORT;
				}
				else if (isSlow(i, o))
				{
					l1 = "STALLED " + ageMin(i) + "m";
					l2 = stepUp > o.getPrice() ? "rebuy " + fmt(stepUp) : "margin thin";
					c = MOVE;
				}
				else
				{
					l1 = "KEEP ✓";
					c = KEEP;
				}
			}
			else
			{
				// THE FLOOR UNDER EVERY SELL SUGGESTION: what was actually paid.
				// A reprice below breakeven is not a reprice, it is a realized
				// loss, and it gets said in those words or not at all.
				final long avg = flips.avgCost(o.getItemId());
				final long breakeven = avg >= 0 ? FlipService.breakevenSell(avg) : -1;
				final long floor = Math.max(low, breakeven);
				final long heldExtra = heldInInventory(o.getItemId());
				final long stepDown = Math.max(o.getPrice() - spreadStep, floor);
				final long exitNowLoss = avg >= 0
					? avg - (low - FlipService.geTax((int) low)) : 0;
				if (heldExtra > 0)
				{
					l1 = "ADD " + fmt(heldExtra) + " MORE";
					l2 = "relist all as one";
					c = MOVE;
				}
				else if (breakeven > 0 && o.getPrice() < breakeven)
				{
					final long lossEa = avg - (o.getPrice() - FlipService.geTax(o.getPrice()));
					l1 = "BELOW COST";
					l2 = "−" + fmt(lossEa) + "/ea if it fills";
					c = ABORT;
				}
				else if (breakeven > 0 && high < breakeven)
				{
					l1 = "UNDER WATER";
					l2 = "hold · exit −" + fmt(Math.max(1, exitNowLoss)) + "/ea";
					c = ABORT;
				}
				else if (o.getPrice() > high)
				{
					l1 = "CANCEL";
					l2 = "relist " + fmt(Math.max(Math.min(high - 1, stepDown), floor));
					c = MOVE;
				}
				else if (isDead(i, o))
				{
					l1 = "DEAD " + placedMin(i) + "m";
					l2 = stepDown < o.getPrice()
						? "resell " + fmt(stepDown) + (stepDown == breakeven ? " =BE" : "")
						: breakeven > 0 && o.getPrice() <= breakeven ? "at breakeven · hold" : "at floor · hold";
					c = ABORT;
				}
				else if (isSlow(i, o))
				{
					l1 = "STALLED " + ageMin(i) + "m";
					l2 = stepDown < o.getPrice()
						? "resell " + fmt(stepDown) + (stepDown == breakeven ? " =BE" : "")
						: breakeven > 0 && o.getPrice() <= breakeven ? "at breakeven · hold" : "at floor";
					c = MOVE;
				}
				else
				{
					l1 = "KEEP ✓";
					c = KEEP;
				}
			}

			final Rectangle b = slot.getBounds();
			if (b == null)
			{
				continue;
			}
			// offer age timer, top-right of the slot (persists across restarts)
			if (offerPlaced[i] > 0)
			{
				final long secs = (System.currentTimeMillis() - offerPlaced[i]) / 1000;
				final String age = secs < 60 ? secs + "s"
					: secs < 3600 ? (secs / 60) + "m"
					: (secs / 3600) + "h" + ((secs % 3600) / 60) + "m";
				g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
				final int aw = g.getFontMetrics().stringWidth(age);
				g.setColor(new Color(10, 10, 14, 200));
				g.fillRoundRect(b.x + b.width - aw - 11, b.y + 2, aw + 9, 14, 7, 7);
				g.setColor(secs > 1800 ? ABORT : secs > 300 ? MOVE : new Color(255, 255, 0));
				g.drawString(age, b.x + b.width - aw - 6, b.y + 13);
			}

			// the stamp: one or two lines, CLAMPED inside this slot's box
			stamp(g, b, l1, l2, c);
		}
		return null;
	}

	/** Draw the verdict inside the slot — never wider than the slot itself. */
	private static void stamp(Graphics2D g, Rectangle b, String l1, String l2, Color c)
	{
		final int maxW = b.width - 6;
		final int lines = l2 == null ? 1 : 2;
		final int boxH = lines * 14 + 4;
		final int by = b.y + b.height - boxH - 2;
		g.setColor(new Color(10, 10, 14, 225));
		g.fillRoundRect(b.x + 3, by, b.width - 6, boxH, 7, 7);
		g.setColor(c);
		g.drawRoundRect(b.x + 3, by, b.width - 6, boxH, 7, 7);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
		g.drawString(clamp(g, l1, maxW - 8), b.x + 7, by + 12);
		if (l2 != null)
		{
			g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
			g.drawString(clamp(g, l2, maxW - 8), b.x + 7, by + 26);
		}
	}

	private static String clamp(Graphics2D g, String s, int maxPx)
	{
		if (g.getFontMetrics().stringWidth(s) <= maxPx)
		{
			return s;
		}
		String a = s;
		while (a.length() > 2 && g.getFontMetrics().stringWidth(a + "…") > maxPx)
		{
			a = a.substring(0, a.length() - 1);
		}
		return a + "…";
	}

	private long heldInInventory(int itemId)
	{
		final net.runelite.api.ItemContainer inv =
			client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
		if (inv == null)
		{
			return 0;
		}
		long held = 0;
		for (net.runelite.api.Item it : inv.getItems())
		{
			if (it != null && it.getId() > 0
				&& itemManager.canonicalize(it.getId()) == itemId)
			{
				held += it.getQuantity();
			}
		}
		return held;
	}

	/**
	 * How far one reprice is allowed to move. A quarter of the spread is the
	 * shape, but the hard limit is 2% of what the item is actually worth — one
	 * tax-worth. A stalled offer wants an undercut; giving away a quarter of a
	 * wide book in one step is capitulation, and on a book whose high side is
	 * inflated it hands back the whole margin the offer was placed for. If a 2%
	 * ladder never clears, the item is the problem, not the price.
	 */
	static long repriceStep(long low, long high)
	{
		return Math.max(1, Math.min((high - low) / 4, Math.max(1, low / 50)));
	}

	private boolean isDead(int slot, GrandExchangeOffer o)
	{
		// unfilled across 30+ wall-clock minutes (offline time counts —
		// the market had all that time and said no)
		return offerPlaced[slot] > 0 && o.getQuantitySold() < o.getTotalQuantity()
			&& System.currentTimeMillis() - offerPlaced[slot] > 30 * 60_000;
	}

	private long placedMin(int slot)
	{
		return (System.currentTimeMillis() - offerPlaced[slot]) / 60_000;
	}

	private boolean isSlow(int slot, GrandExchangeOffer o)
	{
		// priced right but nothing has moved in 5+ min (partials included)
		return offerStarts[slot] > 0
			&& o.getQuantitySold() < o.getTotalQuantity()
			&& System.currentTimeMillis() - offerStarts[slot] > 5 * 60_000;
	}

	private long ageMin(int slot)
	{
		return (System.currentTimeMillis() - offerStarts[slot]) / 60_000;
	}

	private static String fmt(long v)
	{
		return String.format("%,d", v);
	}
}
