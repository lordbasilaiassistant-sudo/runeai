package com.runeai;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Rune — a world-space pet, not a HUD element. Follows the player one tile
 * behind like a real OSRS pet (glides between tiles, teleport-catchup when
 * left behind), speaks in overhead yellow, and celebrates GE fills with a
 * hop and sparkles. Drawn ABOVE_SCENE so interfaces cover it naturally.
 */
public class MascotOverlay extends Overlay
{
	private static final int PX = 2;              // logical pixel size (pet-scale)
	private static final int GW = 26;
	private static final int GH = 24;

	private static final Color OUTLINE = new Color(24, 20, 16);
	private static final Color BASE = new Color(78, 105, 173);
	private static final Color SHADE = new Color(52, 70, 122);
	private static final Color LIGHT = new Color(118, 148, 210);
	private static final Color EYE_W = new Color(236, 232, 218);
	private static final Color MOUTH_D = new Color(30, 18, 24);
	private static final Color MOUTH_R = new Color(122, 46, 56);
	private static final Color OSRS_YELLOW = new Color(255, 255, 0);
	private static final Color GOLD = new Color(255, 200, 0);
	private static final long MOVE_MS = 600;      // one game tick glide

	private final Client client;
	private final RuneAIConfig config;
	private final VoicePlayer voice;

	private volatile WorldPoint petTile;
	private volatile WorldPoint petPrevTile;
	private volatile long moveStartMs;

	private volatile String celebrateText;
	private volatile long celebrateUntilMs;

	private long lastBlinkAt;
	private long blinkEvery = 3400;

	@Inject
	MascotOverlay(Client client, RuneAIConfig config, VoicePlayer voice)
	{
		this.client = client;
		this.config = config;
		this.voice = voice;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Called each tick by the plugin's follower logic. */
	void setPetTiles(WorldPoint prev, WorldPoint cur, long moveStart)
	{
		petPrevTile = prev;
		petTile = cur;
		moveStartMs = moveStart;
	}

	/** GE fill / level up celebration: hop + sparkles + overhead cheer. */
	void celebrate(String text)
	{
		celebrateText = text;
		celebrateUntilMs = System.currentTimeMillis() + 3000;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showMascot() || petTile == null)
		{
			return null;
		}

		final long t = System.currentTimeMillis();

		// glide between prev and current tile, like a follower walking
		LocalPoint cur = LocalPoint.fromWorld(client, petTile);
		if (cur == null)
		{
			return null;
		}
		LocalPoint pos = cur;
		if (petPrevTile != null && t - moveStartMs < MOVE_MS)
		{
			final LocalPoint prev = LocalPoint.fromWorld(client, petPrevTile);
			if (prev != null)
			{
				final double f = (t - moveStartMs) / (double) MOVE_MS;
				pos = new LocalPoint(
					(int) (prev.getX() + (cur.getX() - prev.getX()) * f),
					(int) (prev.getY() + (cur.getY() - prev.getY()) * f));
			}
		}

		final Point c = Perspective.localToCanvas(client, pos, client.getPlane(), 0);
		if (c == null)
		{
			return null;
		}

		final boolean celebrating = t < celebrateUntilMs;
		final float mouth = voice.getMouth();
		String saying = voice.getSpeakingText();
		if (saying == null && celebrating)
		{
			saying = celebrateText;
		}

		// celebration = big hops; idle = gentle bob (whole pixels, retro)
		final int hop = celebrating
			? (int) (10 * Math.abs(Math.sin(t / 130.0)))
			: (int) Math.round(1.2 * Math.sin(t / 400.0)) * PX;

		final int spriteW = GW * PX;
		final int ox = c.getX() - spriteW / 2;
		final int oy = c.getY() - GH * PX - hop;

		// blink
		boolean blink = false;
		final long since = t - lastBlinkAt;
		if (since > blinkEvery)
		{
			lastBlinkAt = t;
			blinkEvery = 2800 + (long) (Math.random() * 2600);
		}
		else if (since < 130)
		{
			blink = true;
		}

		// ground shadow at the tile
		g.setColor(new Color(0, 0, 0, 70));
		g.fillOval(c.getX() - 16, c.getY() - 6, 32, 8);

		drawSprite(g, ox, oy, mouth, blink);

		// celebration sparkles
		if (celebrating)
		{
			for (int i = 0; i < 5; i++)
			{
				final double a = t / 150.0 + i * 1.26;
				final int sx = (int) (c.getX() + 26 * Math.cos(a));
				final int sy = (int) (oy + 10 + 14 * Math.sin(a * 1.7));
				g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(),
					(int) (120 + 100 * Math.sin(a * 2))));
				g.fillRect(sx, sy, 3, 3);
			}
		}

		// overhead text — classic OSRS yellow with black shadow
		if (saying != null)
		{
			g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
			final FontMetrics fm = g.getFontMetrics();
			int ty = oy - 6;
			for (String line : wrap(saying, fm, 170))
			{
				final int tx = c.getX() - fm.stringWidth(line) / 2;
				g.setColor(Color.BLACK);
				g.drawString(line, tx + 1, ty + 1);
				g.setColor(celebrating ? GOLD : OSRS_YELLOW);
				g.drawString(line, tx, ty);
				ty += 14;
			}
		}

		return null;
	}

	private void drawSprite(Graphics2D g, int ox, int oy, float mouth, boolean blink)
	{
		final double cx = 12.5, cy = 12.5, r = 8.6;
		final Color[][] grid = new Color[GH][GW];

		for (int y = 0; y < GH; y++)
		{
			for (int x = 0; x < GW; x++)
			{
				final double dx = x - cx, dy = y - cy;
				if (Math.sqrt(dx * dx + dy * dy) <= r)
				{
					if (dx - dy < -6.5)
					{
						grid[y][x] = LIGHT;
					}
					else if (dx - dy > 5.5 || dy > 6.2)
					{
						grid[y][x] = SHADE;
					}
					else
					{
						grid[y][x] = BASE;
					}
				}
			}
		}
		fillTri(grid, 6, 4, -1);
		fillTri(grid, 19, 4, 1);
		stamp(grid, 8, 21, 3, 2, SHADE);
		stamp(grid, 15, 21, 3, 2, SHADE);

		final Color[][] outlined = new Color[GH][GW];
		for (int y = 0; y < GH; y++)
		{
			System.arraycopy(grid[y], 0, outlined[y], 0, GW);
		}
		for (int y = 0; y < GH; y++)
		{
			for (int x = 0; x < GW; x++)
			{
				if (grid[y][x] == null)
				{
					continue;
				}
				if (x == 0 || x == GW - 1 || y == 0 || y == GH - 1
					|| grid[y][x - 1] == null || grid[y][x + 1] == null
					|| grid[y - 1][x] == null || grid[y + 1][x] == null)
				{
					outlined[y][x] = OUTLINE;
				}
			}
		}

		if (blink)
		{
			stamp(outlined, 8, 10, 3, 1, SHADE);
			stamp(outlined, 15, 10, 3, 1, SHADE);
		}
		else
		{
			stamp(outlined, 8, 9, 3, 3, EYE_W);
			stamp(outlined, 15, 9, 3, 3, EYE_W);
			stamp(outlined, 9, 10, 1, 1, OUTLINE);
			stamp(outlined, 16, 10, 1, 1, OUTLINE);
		}

		if (mouth > 0.5f)
		{
			stamp(outlined, 11, 14, 4, 3, MOUTH_D);
			stamp(outlined, 12, 16, 2, 1, MOUTH_R);
		}
		else if (mouth > 0.15f)
		{
			stamp(outlined, 11, 15, 4, 2, MOUTH_D);
		}
		else
		{
			stamp(outlined, 11, 15, 4, 1, MOUTH_D);
		}

		for (int y = 0; y < GH; y++)
		{
			for (int x = 0; x < GW; x++)
			{
				if (outlined[y][x] != null)
				{
					g.setColor(outlined[y][x]);
					g.fillRect(ox + x * PX, oy + y * PX, PX, PX);
				}
			}
		}
	}

	private void fillTri(Color[][] grid, int baseX, int topY, int dir)
	{
		for (int i = 0; i < 3; i++)
		{
			for (int j = 0; j <= i; j++)
			{
				final int x = baseX + dir * j;
				final int y = topY + i;
				if (y >= 0 && y < GH && x >= 0 && x < GW)
				{
					grid[y][x] = i == 0 ? LIGHT : BASE;
				}
			}
		}
	}

	private void stamp(Color[][] grid, int x0, int y0, int w, int h, Color c)
	{
		for (int y = y0; y < y0 + h && y < GH; y++)
		{
			for (int x = x0; x < x0 + w && x < GW; x++)
			{
				if (y >= 0 && x >= 0)
				{
					grid[y][x] = c;
				}
			}
		}
	}

	private java.util.List<String> wrap(String text, FontMetrics fm, int maxW)
	{
		final java.util.List<String> out = new java.util.ArrayList<>();
		final StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			if (line.length() > 0 && fm.stringWidth(line + " " + word) > maxW)
			{
				out.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}
		out.add(line.toString());
		return out;
	}
}
