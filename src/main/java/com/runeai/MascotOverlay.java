package com.runeai;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Rune — the RuneAI companion, drawn as an OSRS-style pixel pet:
 * chunky pixels, flat shading bands, dark outline, movement quantized to
 * whole pixels, and classic yellow overhead text instead of a bubble.
 * Lip-syncs (3 retro visemes) to the live voice envelope. Alt-drag to move.
 */
public class MascotOverlay extends Overlay
{
	private static final int PX = 3;              // logical pixel size
	private static final int GW = 26;             // grid width
	private static final int GH = 24;             // grid height
	private static final int W = 220;             // overlay box (room for text)
	private static final int H = 130;

	// flat OSRS-ish palette — no gradients
	private static final Color OUTLINE = new Color(24, 20, 16);
	private static final Color BASE = new Color(78, 105, 173);
	private static final Color SHADE = new Color(52, 70, 122);
	private static final Color LIGHT = new Color(118, 148, 210);
	private static final Color EYE_W = new Color(236, 232, 218);
	private static final Color MOUTH_D = new Color(30, 18, 24);
	private static final Color MOUTH_R = new Color(122, 46, 56);
	private static final Color OSRS_YELLOW = new Color(255, 255, 0);

	private final RuneAIConfig config;
	private final VoicePlayer voice;

	private long lastBlinkAt;
	private long blinkEvery = 3400;

	@Inject
	MascotOverlay(RuneAIConfig config, VoicePlayer voice)
	{
		this.config = config;
		this.voice = voice;
		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showMascot())
		{
			return null;
		}

		final long t = System.currentTimeMillis();
		final float mouth = voice.getMouth();
		final String saying = voice.getSpeakingText();

		// sprite origin, bottom-center of the box; bob in WHOLE pixels (retro)
		final int spriteW = GW * PX;
		final int ox = (W - spriteW) / 2;
		final int bobPx = (int) Math.round(1.2 * Math.sin(t / 400.0));
		final int oy = H - GH * PX - 6 + bobPx * PX;

		// blink state
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

		drawSprite(g, ox, oy, mouth, blink);

		// classic OSRS overhead text: yellow with hard black shadow
		if (saying != null)
		{
			g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
			final FontMetrics fm = g.getFontMetrics();
			final List<String> lines = wrap(saying, fm, W - 8);
			int ty = oy - 6 - (lines.size() - 1) * 14;
			for (String line : lines)
			{
				final int tx = (W - fm.stringWidth(line)) / 2;
				g.setColor(Color.BLACK);
				g.drawString(line, tx + 1, ty + 1);
				g.setColor(OSRS_YELLOW);
				g.drawString(line, tx, ty);
				ty += 14;
			}
		}

		return new Dimension(W, H);
	}

	/**
	 * Procedural pixel imp: round body with flat shading bands, two horns,
	 * stubby feet — every pixel snapped to the grid, outlined like a sprite.
	 */
	private void drawSprite(Graphics2D g, int ox, int oy, float mouth, boolean blink)
	{
		final double cx = 12.5, cy = 12.5, r = 8.6;   // body circle in grid space
		final Color[][] grid = new Color[GH][GW];

		// body: banded flat shading, no gradients
		for (int y = 0; y < GH; y++)
		{
			for (int x = 0; x < GW; x++)
			{
				final double dx = x - cx, dy = y - cy;
				final double d = Math.sqrt(dx * dx + dy * dy);
				if (d <= r)
				{
					if (dx - dy < -6.5)
					{
						grid[y][x] = LIGHT;       // top-left band
					}
					else if (dx - dy > 5.5 || dy > 6.2)
					{
						grid[y][x] = SHADE;       // bottom-right band
					}
					else
					{
						grid[y][x] = BASE;
					}
				}
			}
		}

		// horns: two chunky triangles
		fillTri(grid, 6, 4, -1);
		fillTri(grid, 19, 4, 1);

		// feet: two stubs under the body
		stamp(grid, 8, 21, 3, 2, SHADE);
		stamp(grid, 15, 21, 3, 2, SHADE);

		// outline: any body pixel touching emptiness
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
				final boolean edge = x == 0 || x == GW - 1 || y == 0 || y == GH - 1
					|| grid[y][x - 1] == null || grid[y][x + 1] == null
					|| grid[y - 1][x] == null || grid[y + 1][x] == null;
				if (edge)
				{
					outlined[y][x] = OUTLINE;
				}
			}
		}

		// eyes (3x3 whites, 1px pupil) or eyelid band when blinking
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

		// mouth: 3 retro visemes from the live envelope
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

		// blit
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

	/** Small horn triangle pointing up, mirrored by dir. */
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

	private List<String> wrap(String text, FontMetrics fm, int maxW)
	{
		final List<String> out = new ArrayList<>();
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
