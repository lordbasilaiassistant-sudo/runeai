package com.runeai;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
	private static final int W = 250;

	private final Client client;
	private final RuneAIConfig config;
	private final FlipService flips;

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
		final List<FlipService.Flip> top = flips.getTopFlips();

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final int rows = top.isEmpty() ? 1 : Math.min(5, top.size());
		final int h = 34 + rows * 27 + 6;

		g.setColor(new Color(12, 12, 18, 235));
		g.fillRoundRect(0, 0, W, h, 10, 10);
		g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 200));
		g.setStroke(new BasicStroke(1.5f));
		g.drawRoundRect(0, 0, W, h, 10, 10);

		g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
		g.setColor(GOLD);
		g.drawString("RuneAI · flips for YOUR budget", 10, 20);

		int y = 40;
		if (top.isEmpty())
		{
			g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
			g.setColor(Color.LIGHT_GRAY);
			g.drawString("fetching live prices…", 10, y);
		}
		for (int i = 0; i < rows && i < top.size(); i++)
		{
			final FlipService.Flip f = top.get(i);
			g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
			g.setColor(Color.WHITE);
			g.drawString(trunc(f.getName(), 26), 10, y);
			g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
			g.setColor(GOLD);
			g.drawString(String.format("buy %,d  →  sell %,d   +%,d (%.1f%%)",
				f.getBuyAt(), f.getSellAt(), f.getNet(), f.getRoi()), 10, y + 13);
			y += 27;
		}
		return new Dimension(W, h);
	}

	private static String trunc(String s, int n)
	{
		return s.length() <= n ? s : s.substring(0, n - 1) + "…";
	}
}
