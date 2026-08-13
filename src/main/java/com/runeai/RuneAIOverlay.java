package com.runeai;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Perspective;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * RuneAI's hands-on-screen: outlines what matters, marks the tile to click.
 * Trigger sources (rules now, model later) call flashTile()/setAlert() —
 * this class only draws.
 */
public class RuneAIOverlay extends Overlay
{
	static final Color DANGER = new Color(255, 60, 60);
	static final Color TARGET = new Color(0, 180, 255);
	static final Color LOOT = new Color(255, 200, 0);
	static final Color GUIDE = new Color(0, 255, 140);

	private final Client client;
	private final RuneAIConfig config;
	private final ModelOutlineRenderer outliner;
	/** Tiles marked at once. More than a handful on screen is noise, not guidance. */
	private static final int MAX_FLASHES = 16;

	private final List<TileFlash> flashes = new CopyOnWriteArrayList<>();
	private volatile String alertText;
	private volatile int alertExpiresAtTick;
	private volatile long alertShownAtMs;

	private static final class TileFlash
	{
		final WorldPoint point;
		final Color color;
		final String label;
		final int expiresAtTick;
		final long createdMs;

		TileFlash(WorldPoint point, Color color, String label, int expiresAtTick)
		{
			this.point = point;
			this.color = color;
			this.label = label;
			this.expiresAtTick = expiresAtTick;
			this.createdMs = System.currentTimeMillis();
		}
	}

	@Inject
	RuneAIOverlay(Client client, RuneAIConfig config, ModelOutlineRenderer outliner)
	{
		this.client = client;
		this.config = config;
		this.outliner = outliner;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/**
	 * Flash a tile with a label for N ticks — the "click here" primitive.
	 *
	 * <p>Expiry is pruned HERE as well as in {@code render()}, because render is
	 * not guaranteed to run: it returns on its first line when {@code showOverlays}
	 * is off, and it does not run at all while the client is not drawing. The
	 * triggers in {@code RuneAIPlugin} keep calling this either way, so leaving the
	 * only cleanup inside the draw loop meant an unbounded list for anyone playing
	 * with the overlays switched off. Draw-only still holds — this is list
	 * hygiene, not a decision about what to show.
	 */
	void flashTile(WorldPoint point, Color color, String label, int expiresAtTick)
	{
		final int now = client.getTickCount();
		flashes.removeIf(f -> now >= f.expiresAtTick);
		while (flashes.size() >= MAX_FLASHES)
		{
			flashes.remove(0);
		}
		flashes.add(new TileFlash(point, color, label, expiresAtTick));
	}

	/** Big top-center warning — the "do this NOW" primitive. */
	void setAlert(String text, int expiresAtTick)
	{
		if (!text.equals(alertText) || client.getTickCount() >= alertExpiresAtTick)
		{
			alertShownAtMs = System.currentTimeMillis();
		}
		alertText = text;
		alertExpiresAtTick = expiresAtTick;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showOverlays())
		{
			return null;
		}
		final Player lp = client.getLocalPlayer();
		if (lp == null)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final long ms = System.currentTimeMillis();

		// combat awareness: red = attacking you, cyan = your target
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || npc.getName() == null)
			{
				continue;
			}
			if (npc.getInteracting() == lp && npc.getCombatLevel() > 0)
			{
				outliner.drawOutline(npc, 2, DANGER, 4);
				labelActor(g, npc, npc.getName(), DANGER);
			}
			else if (lp.getInteracting() == npc)
			{
				outliner.drawOutline(npc, 2, TARGET, 4);
			}
		}

		// tile markers
		final int now = client.getTickCount();
		flashes.removeIf(f -> now >= f.expiresAtTick);
		for (TileFlash f : flashes)
		{
			final LocalPoint loc = LocalPoint.fromWorld(client, f.point);
			if (loc == null)
			{
				continue;
			}
			final Polygon poly = Perspective.getCanvasTilePoly(client, loc);
			if (poly == null)
			{
				continue;
			}

			// eased breathing pulse + fade out over the final 2 ticks
			final double breathe = 0.5 + 0.5 * Math.sin((ms - f.createdMs) / 220.0);
			final double eased = breathe * breathe * (3 - 2 * breathe); // smoothstep
			final int ticksLeft = f.expiresAtTick - now;
			final float fade = Math.min(1f, ticksLeft / 2f);

			drawTileMarker(g, poly, f.color, (float) eased, fade);
			drawBobbingArrow(g, loc, f.color, ms, fade);

			if (f.label != null)
			{
				final Point text = Perspective.getCanvasTextLocation(client, g, loc, f.label, 0);
				if (text != null)
				{
					g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
					OverlayUtil.renderTextLocation(g, text, f.label,
						withAlpha(f.color, (int) (255 * fade)));
				}
			}
		}

		// top-center alert banner with slide+fade entrance
		if (alertText != null && now < alertExpiresAtTick)
		{
			final String text = alertText;
			final double in = Math.min(1.0, (ms - alertShownAtMs) / 250.0);
			final double easeIn = in * in * (3 - 2 * in);
			final double pulse = 0.5 + 0.5 * Math.sin(ms / 160.0);

			g.setFont(g.getFont().deriveFont(Font.BOLD, 20f));
			final int w = g.getFontMetrics().stringWidth(text);
			final int x = (client.getCanvasWidth() - w) / 2;
			final int y = (int) (30 + 30 * easeIn);

			g.setColor(new Color(20, 0, 0, (int) (180 * easeIn)));
			g.fillRoundRect(x - 14, y - 24, w + 28, 36, 12, 12);
			g.setColor(withAlpha(DANGER, (int) ((120 + 100 * pulse) * easeIn)));
			g.setStroke(new BasicStroke(2f));
			g.drawRoundRect(x - 14, y - 24, w + 28, 36, 12, 12);
			g.setColor(withAlpha(Color.WHITE, (int) (235 * easeIn)));
			g.drawString(text, x, y);
		}

		return null;
	}

	/** Corner brackets + soft glow fill — reads instantly, doesn't smother the tile. */
	private void drawTileMarker(Graphics2D g, Polygon poly, Color color, float pulse, float fade)
	{
		g.setColor(withAlpha(color, (int) ((14 + 30 * pulse) * fade)));
		g.fill(poly);

		// wide faint glow pass, then crisp bracket pass
		final int n = poly.npoints;
		for (int pass = 0; pass < 2; pass++)
		{
			g.setStroke(new BasicStroke(pass == 0 ? 5f : 2.2f,
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(withAlpha(color, (int) ((pass == 0 ? 35 + 40 * pulse : 160 + 90 * pulse) * fade)));
			for (int i = 0; i < n; i++)
			{
				final int x = poly.xpoints[i], y = poly.ypoints[i];
				final int xn = poly.xpoints[(i + 1) % n], yn = poly.ypoints[(i + 1) % n];
				final int xp = poly.xpoints[(i + n - 1) % n], yp = poly.ypoints[(i + n - 1) % n];
				g.drawLine(x, y, x + (int) ((xn - x) * 0.28), y + (int) ((yn - y) * 0.28));
				g.drawLine(x, y, x + (int) ((xp - x) * 0.28), y + (int) ((yp - y) * 0.28));
			}
		}
	}

	/** Floating arrow bobbing above the tile — the "here" pointer. */
	private void drawBobbingArrow(Graphics2D g, LocalPoint loc, Color color, long ms, float fade)
	{
		final Point c = Perspective.localToCanvas(client, loc, client.getPlane(), 0);
		if (c == null)
		{
			return;
		}
		final int bob = (int) (5 * Math.sin(ms / 170.0));
		final int ax = c.getX();
		final int ay = c.getY() - 34 + bob;

		final Polygon arrow = new Polygon(
			new int[]{ax - 8, ax + 8, ax},
			new int[]{ay, ay, ay + 11},
			3);
		g.setColor(withAlpha(Color.BLACK, (int) (110 * fade)));
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(arrow);
		g.setColor(withAlpha(color, (int) (230 * fade)));
		g.fill(arrow);
	}

	private static Color withAlpha(Color c, int alpha)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(),
			Math.max(0, Math.min(255, alpha)));
	}

	private void labelActor(Graphics2D g, NPC npc, String text, Color color)
	{
		final Point p = npc.getCanvasTextLocation(g, text, npc.getLogicalHeight() + 20);
		if (p != null)
		{
			g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
			OverlayUtil.renderTextLocation(g, p, text, color);
		}
	}
}
