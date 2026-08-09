package com.runeai;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Rune — the RuneAI mascot. A little living wisp that lip-syncs to the
 * voice lines, blinks, bobs, and shows what it's saying in a bubble.
 * Alt-drag to park it anywhere.
 */
public class MascotOverlay extends Overlay
{
	private static final int W = 170;
	private static final int H = 150;
	private static final Color BODY_A = new Color(0, 180, 255);
	private static final Color BODY_B = new Color(110, 70, 255);

	private final RuneAIConfig config;
	private final VoicePlayer voice;

	private long lastBlinkAt;
	private long blinkEvery = 3200;

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

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final long t = System.currentTimeMillis();
		final float mouth = voice.getMouth();
		final String saying = voice.getSpeakingText();

		// body anchor bottom-center, leave headroom for the bubble
		final int cx = W / 2;
		final int cy = H - 44;
		final double bob = 3.5 * Math.sin(t / 420.0);
		final double squash = 1 + 0.045 * Math.sin(t / 420.0 + Math.PI / 2)
			+ 0.10 * mouth; // talking adds bounce

		final int rx = (int) (30 / Math.sqrt(squash));
		final int ry = (int) (30 * squash);
		final int by = (int) (cy + bob);

		// soft ground shadow
		g.setColor(new Color(0, 0, 0, 70));
		g.fillOval(cx - 24, H - 18, 48, 10);

		// outer glow, breathing
		final float glowPulse = (float) (0.5 + 0.5 * Math.sin(t / 700.0));
		final RadialGradientPaint glow = new RadialGradientPaint(
			new Point2D.Float(cx, by), rx + 22,
			new float[]{0.5f, 1f},
			new Color[]{new Color(BODY_A.getRed(), BODY_A.getGreen(), BODY_A.getBlue(),
				(int) (55 + 30 * glowPulse)), new Color(0, 0, 0, 0)});
		g.setPaint(glow);
		g.fillOval(cx - rx - 22, by - ry - 22, (rx + 22) * 2, (ry + 22) * 2);

		// body
		g.setPaint(new GradientPaint(cx - rx, by - ry, BODY_A, cx + rx, by + ry, BODY_B));
		g.fill(new Ellipse2D.Double(cx - rx, by - ry, rx * 2, ry * 2));
		// rim light
		g.setColor(new Color(255, 255, 255, 60));
		g.setStroke(new BasicStroke(2f));
		g.drawArc(cx - rx + 3, by - ry + 3, rx * 2 - 6, ry * 2 - 6, 60, 120);

		// blink state
		double blink = 0;
		final long since = t - lastBlinkAt;
		if (since > blinkEvery)
		{
			lastBlinkAt = t;
			blinkEvery = 2600 + (long) (Math.random() * 2600);
		}
		else if (since < 150)
		{
			blink = Math.sin(Math.PI * since / 150.0);
		}

		// eyes with drifting pupils
		final double px = 2.2 * Math.sin(t / 1700.0);
		final double py = 1.6 * Math.cos(t / 2300.0);
		for (int side = -1; side <= 1; side += 2)
		{
			final int ex = cx + side * 12;
			final int ey = by - 8;
			final int eyeH = (int) Math.max(1, 11 * (1 - blink));
			g.setColor(Color.WHITE);
			g.fillOval(ex - 7, ey - eyeH / 2 - (11 - eyeH) / 4, 14, eyeH);
			if (blink < 0.7)
			{
				g.setColor(new Color(25, 20, 60));
				g.fillOval((int) (ex - 3 + px), (int) (ey - 3 + py), 7, 7);
				g.setColor(Color.WHITE);
				g.fillOval((int) (ex - 1 + px), (int) (ey - 2.5 + py), 2, 2);
			}
		}

		// mouth — lip-synced to playback envelope
		final int mw = (int) (14 + 8 * mouth);
		final int mh = (int) (2 + 15 * mouth);
		g.setColor(new Color(25, 15, 45));
		g.fill(new RoundRectangle2D.Double(cx - mw / 2.0, by + 8, mw, mh, 8, 8));
		if (mouth > 0.35)
		{
			g.setColor(new Color(255, 120, 130, 180));
			g.fill(new RoundRectangle2D.Double(cx - mw / 4.0, by + 8 + mh * 0.55, mw / 2.0, mh * 0.4, 6, 6));
		}

		// tiny orbiting sparkles
		for (int i = 0; i < 3; i++)
		{
			final double a = t / (900.0 + i * 220) + i * 2.1;
			final int sx = (int) (cx + (rx + 10) * Math.cos(a));
			final int sy = (int) (by + (ry * 0.5 + 6) * Math.sin(a) - 8);
			final int alpha = (int) (90 + 90 * Math.sin(a * 3));
			g.setColor(new Color(255, 255, 255, Math.max(0, Math.min(255, alpha))));
			g.fillOval(sx, sy, 3, 3);
		}

		// speech bubble
		if (saying != null)
		{
			drawBubble(g, saying, cx, by - ry - 8);
		}

		return new Dimension(W, H);
	}

	private void drawBubble(Graphics2D g, String text, int anchorX, int anchorY)
	{
		g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
		final FontMetrics fm = g.getFontMetrics();

		// wrap to the overlay width
		final List<String> wrapped = new ArrayList<>();
		final StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			if (fm.stringWidth(line + " " + word) > W - 30 && line.length() > 0)
			{
				wrapped.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}
		wrapped.add(line.toString());

		int bw = 0;
		for (String l : wrapped)
		{
			bw = Math.max(bw, fm.stringWidth(l));
		}
		bw += 18;
		final int bh = wrapped.size() * 14 + 12;
		final int bx = Math.max(2, Math.min(W - bw - 2, anchorX - bw / 2));
		final int by = Math.max(2, anchorY - bh - 8);

		g.setColor(new Color(15, 15, 25, 235));
		g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 12, 12));
		g.setColor(new Color(BODY_A.getRed(), BODY_A.getGreen(), BODY_A.getBlue(), 190));
		g.setStroke(new BasicStroke(1.4f));
		g.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 12, 12));
		// tail
		final int tx = Math.max(bx + 10, Math.min(bx + bw - 16, anchorX - 3));
		g.fillPolygon(new int[]{tx, tx + 8, tx + 2}, new int[]{by + bh, by + bh, by + bh + 7}, 3);

		g.setColor(Color.WHITE);
		int ty = by + 16;
		for (String l : wrapped)
		{
			g.drawString(l, bx + 9, ty);
			ty += 14;
		}
	}
}
