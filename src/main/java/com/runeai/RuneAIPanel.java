package com.runeai;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.Cursor;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public class RuneAIPanel extends PluginPanel
{
	private static final Color ACCENT = new Color(0, 180, 255);
	private static final Color OK_GREEN = new Color(0, 200, 120);

	private final JLabel stateValue = new JLabel("STARTING");
	private final JLabel playerValue = new JLabel("—");
	private final JLabel npcValue = new JLabel("0");
	private final JLabel playersValue = new JLabel("0");
	private final JLabel eventsValue = new JLabel("0");
	private final JLabel activityValue = new JLabel("—");
	private final JLabel pnlValue = new JLabel("0 gp");
	private final JLabel bondValue = new JLabel("—");

	public RuneAIPanel()
	{
		super();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		final JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("RuneAI");
		title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
		title.setForeground(ACCENT);
		title.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel subtitle = new JLabel("data layer active ✓");
		subtitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		subtitle.setForeground(OK_GREEN);
		subtitle.setAlignmentX(LEFT_ALIGNMENT);

		container.add(title);
		container.add(subtitle);
		container.add(Box.createVerticalStrut(12));

		final JPanel card = new JPanel(new GridLayout(0, 1, 0, 6));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, ACCENT),
			BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		card.setAlignmentX(LEFT_ALIGNMENT);

		card.add(row("Game state", stateValue));
		card.add(row("Player", playerValue));
		card.add(row("Activity", activityValue));
		card.add(row("Session P&L", pnlValue));
		card.add(row("Bond fund", bondValue));
		card.add(row("NPCs loaded", npcValue));
		card.add(row("Players loaded", playersValue));
		card.add(row("Events logged", eventsValue));

		container.add(card);
		container.add(Box.createVerticalStrut(12));

		final JLabel footer = new JLabel("<html>Recording everything →<br>.runelite\\runeai\\</html>");
		footer.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		footer.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		footer.setAlignmentX(LEFT_ALIGNMENT);
		container.add(footer);
		container.add(Box.createVerticalStrut(14));

		final JButton kofi = new JButton("♥  Support us on Ko-fi");
		kofi.setBackground(new Color(255, 94, 91));
		kofi.setForeground(Color.WHITE);
		kofi.setFocusPainted(false);
		kofi.setBorderPainted(false);
		kofi.setOpaque(true);
		kofi.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		kofi.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		kofi.setAlignmentX(LEFT_ALIGNMENT);
		kofi.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		kofi.setToolTipText("One-time or monthly — you pick. Keeps RuneAI free.");
		kofi.addActionListener(e -> LinkBrowser.browse("https://ko-fi.com/broketobuilt"));
		kofi.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				kofi.setBackground(new Color(255, 122, 119));
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				kofi.setBackground(new Color(255, 94, 91));
			}
		});
		container.add(kofi);

		add(container, BorderLayout.NORTH);
	}

	private JPanel row(String label, JLabel value)
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel l = new JLabel(label);
		l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		value.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		value.setForeground(Color.WHITE);

		p.add(l, BorderLayout.WEST);
		p.add(value, BorderLayout.EAST);
		return p;
	}

	public void setGameState(String state)
	{
		SwingUtilities.invokeLater(() -> stateValue.setText(state));
	}

	public void setPlayer(String name)
	{
		SwingUtilities.invokeLater(() ->
		{
			playerValue.setText(name == null ? "—" : name);
			playerValue.setForeground(name == null ? Color.WHITE : OK_GREEN);
		});
	}

	public void setPnl(long pnl)
	{
		SwingUtilities.invokeLater(() ->
		{
			pnlValue.setText(String.format("%,d gp", pnl));
			pnlValue.setForeground(pnl > 0 ? OK_GREEN : pnl < 0 ? new Color(255, 90, 90) : Color.WHITE);
		});
	}

	public void setBond(boolean members, long worth, long bondPrice, boolean bankKnown)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (members)
			{
				bondValue.setText("members ✓");
				bondValue.setForeground(OK_GREEN);
				return;
			}
			if (bondPrice <= 0)
			{
				bondValue.setText("—");
				return;
			}
			final int pct = (int) Math.min(100, worth * 100 / bondPrice);
			bondValue.setText(String.format("%d%%%s of %,dk", pct, bankKnown ? "" : "*", bondPrice / 1000));
			bondValue.setToolTipText(bankKnown
				? String.format("Total worth %,d gp vs bond %,d gp", worth, bondPrice)
				: "Open your bank once to count everything you own");
			bondValue.setForeground(pct >= 100 ? OK_GREEN : ACCENT);
		});
	}

	public void setActivity(String activity)
	{
		SwingUtilities.invokeLater(() ->
		{
			activityValue.setText(activity);
			activityValue.setForeground("—".equals(activity) ? Color.WHITE : ACCENT);
		});
	}

	public void setCounts(int npcs, int players, long events)
	{
		SwingUtilities.invokeLater(() ->
		{
			npcValue.setText(String.valueOf(npcs));
			playersValue.setText(String.valueOf(players));
			eventsValue.setText(String.valueOf(events));
		});
	}

	static BufferedImage createIcon()
	{
		final int s = 16;
		final BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setPaint(new GradientPaint(0, 0, ACCENT, s, s, new Color(90, 60, 255)));
		g.fillRoundRect(0, 0, s, s, 6, 6);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(4, 12, 8, 3);
		g.drawLine(8, 3, 12, 12);
		g.drawLine(6, 9, 10, 9);
		g.dispose();
		return img;
	}
}
