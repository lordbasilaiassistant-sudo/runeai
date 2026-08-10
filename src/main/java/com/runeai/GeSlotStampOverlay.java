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
 */
public class GeSlotStampOverlay extends Overlay
{
	private static final Color KEEP = new Color(60, 200, 110);
	private static final Color MOVE = new Color(255, 170, 60);
	private static final Color ABORT = new Color(255, 80, 80);

	private final Client client;
	private final RuneAIConfig config;
	private final FlipService flips;
	private final net.runelite.client.game.ItemManager itemManager;

	private volatile long[] offerStarts = new long[8];

	void setOfferStarts(long[] starts)
	{
		offerStarts = starts;
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
			String label;
			Color c;
			final boolean buying = o.getState() == GrandExchangeOfferState.BUYING
				|| o.getState() == GrandExchangeOfferState.BOUGHT;

			if (buying)
			{
				final long margin = (q[1] - FlipService.geTax((int) q[1])) - o.getPrice();
				if (margin <= 0)
				{
					label = "ABORT — margin gone";
					c = ABORT;
				}
				else if (o.getPrice() < low)
				{
					label = "CANCEL · rebuy " + fmt(low + 1);
					c = MOVE;
				}
				else if (isSlow(i, o))
				{
					label = "STALLED " + ageMin(i) + "m · reprice?";
					c = MOVE;
				}
				else
				{
					label = "KEEP ✓";
					c = KEEP;
				}
			}
			else
			{
				final long heldExtra = heldInInventory(o.getItemId());
				if (heldExtra > 0)
				{
					// selling while holding more of the same item wastes the slot:
					// one consolidated offer moves everything
					label = "ADD " + fmt(heldExtra) + " MORE · relist all";
					c = MOVE;
				}
				else if (o.getPrice() > high)
				{
					label = "CANCEL · relist " + fmt(high - 1);
					c = MOVE;
				}
				else if (isSlow(i, o))
				{
					label = "STALLED " + ageMin(i) + "m · undercut?";
					c = MOVE;
				}
				else
				{
					label = "KEEP ✓";
					c = KEEP;
				}
			}

			final Rectangle b = slot.getBounds();
			if (b == null)
			{
				continue;
			}
			g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
			final int tw = g.getFontMetrics().stringWidth(label);
			final int px = b.x + (b.width - tw) / 2 - 5;
			final int py = b.y + b.height - 21;
			g.setColor(new Color(10, 10, 14, 220));
			g.fillRoundRect(px, py, tw + 12, 18, 9, 9);
			g.setColor(c);
			g.drawRoundRect(px, py, tw + 12, 18, 9, 9);
			g.drawString(label, px + 6, py + 14);
		}
		return null;
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
