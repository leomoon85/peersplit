import os
from PIL import Image, ImageDraw, ImageFont

def get_font(size, bold=False):
    font_path = "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf"
    if not os.path.exists(font_path):
        font_path = "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"
    return ImageFont.truetype(font_path, size)

def create_base_canvas(w=1080, h=2400):
    img = Image.new("RGBA", (w, h), (11, 15, 25, 255))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        r = int(10 + (y / h) * 14)
        g = int(14 + (y / h) * 18)
        b = int(24 + (y / h) * 26)
        draw.line([(0, y), (w, y)], fill=(r, g, b, 255))
    return img, draw

def draw_phone_frame(draw, px, py, pw, ph, radius=56):
    draw.rounded_rectangle([px, py, px + pw, py + ph], radius=radius, fill=(18, 24, 38, 255), outline=(51, 65, 85, 255), width=4)
    screen_inset = 12
    sx = px + screen_inset
    sy = py + screen_inset
    sw = pw - screen_inset * 2
    sh = ph - screen_inset * 2
    draw.rounded_rectangle([sx, sy, sx + sw, sy + sh], radius=radius - 8, fill=(15, 23, 42, 255))
    cx = px + pw // 2
    draw.ellipse([cx - 10, sy + 18, cx + 10, sy + 38], fill=(10, 15, 25, 255))
    font_sm = get_font(24)
    draw.text((sx + 36, sy + 22), "09:41", font=font_sm, fill=(203, 213, 225, 255))
    draw.text((sx + sw - 120, sy + 22), "5G  100%", font=font_sm, fill=(203, 213, 225, 255))
    return sx, sy + 64, sw, sh - 64

# SCREENSHOT 1: Dashboard
def render_screen_1():
    img, draw = create_base_canvas()
    draw.text((70, 140), "100% Serverless & Private", font=get_font(64, bold=True), fill=(255, 255, 255))
    draw.text((70, 225), "Split expenses with zero central servers, no cloud tracking,", font=get_font(34), fill=(148, 163, 184))
    draw.text((70, 275), "and no required user accounts or monthly fees.", font=get_font(34), fill=(148, 163, 184))
    
    sx, sy, sw, sh = draw_phone_frame(draw, 90, 370, 900, 1960)
    
    draw.text((sx + 36, sy + 30), "PeerSplit", font=get_font(42, bold=True), fill=(255, 255, 255))
    draw.rounded_rectangle([sx + sw - 180, sy + 32, sx + sw - 36, sy + 76], radius=22, fill=(16, 185, 129, 40), outline=(16, 185, 129, 180), width=2)
    draw.ellipse([sx + sw - 165, sy + 48, sx + sw - 151, sy + 62], fill=(16, 185, 129))
    draw.text((sx + sw - 140, sy + 42), "P2P Sync", font=get_font(22, bold=True), fill=(16, 185, 129))
    
    card_y = sy + 105
    draw.rounded_rectangle([sx + 32, card_y, sx + sw - 32, card_y + 240], radius=28, fill=(30, 41, 59, 255), outline=(51, 65, 85, 255), width=2)
    draw.text((sx + 64, card_y + 36), "TOTAL BALANCE", font=get_font(26, bold=True), fill=(148, 163, 184))
    draw.text((sx + 64, card_y + 75), "+ ₹4,850.00", font=get_font(60, bold=True), fill=(16, 185, 129))
    draw.line([(sx + 64, card_y + 155), (sx + sw - 64, card_y + 155)], fill=(51, 65, 85), width=1)
    draw.text((sx + 64, card_y + 175), "You are owed ₹5,600", font=get_font(28), fill=(16, 185, 129))
    draw.text((sx + sw - 280, card_y + 175), "You owe ₹750", font=get_font(28), fill=(244, 63, 94))
    
    draw.text((sx + 36, card_y + 285), "ACTIVE GROUPS", font=get_font(26, bold=True), fill=(148, 163, 184))
    
    groups = [
        ("Goa Beach Trip", "4 members • INR (₹)", "+ ₹3,200", (16, 185, 129), "9 blocks", "G"),
        ("Flat 402 Roommates", "3 members • INR (₹)", "- ₹750", (244, 63, 94), "24 blocks", "F"),
        ("Hackathon Bengaluru", "5 members • INR (₹)", "+ ₹2,400", (16, 185, 129), "14 blocks", "H"),
        ("Weekend Trekking", "6 members • INR (₹)", "Settled", (148, 163, 184), "18 blocks", "W")
    ]
    
    gy = card_y + 340
    for name, sub, bal, col, blk, initial in groups:
        draw.rounded_rectangle([sx + 32, gy, sx + sw - 32, gy + 145], radius=24, fill=(24, 33, 50, 255), outline=(45, 58, 78, 255), width=2)
        draw.ellipse([sx + 54, gy + 32, sx + 134, gy + 112], fill=(40, 53, 76))
        draw.text((sx + 78, gy + 45), initial, font=get_font(40, bold=True), fill=(56, 189, 248))
        draw.text((sx + 155, gy + 35), name, font=get_font(34, bold=True), fill=(255, 255, 255))
        draw.text((sx + 155, gy + 82), sub, font=get_font(24), fill=(148, 163, 184))
        draw.text((sx + sw - 230, gy + 35), bal, font=get_font(34, bold=True), fill=col)
        draw.text((sx + sw - 210, gy + 82), blk, font=get_font(22), fill=(100, 116, 139))
        gy += 165
        
    fab_x = sx + sw - 140
    fab_y = sy + sh - 170
    draw.ellipse([fab_x, fab_y, fab_x + 90, fab_y + 90], fill=(16, 185, 129))
    draw.text((fab_x + 28, fab_y + 12), "+", font=get_font(60, bold=True), fill=(255, 255, 255))
    return img

# SCREENSHOT 2: Group Ledger & Expenses
def render_screen_2():
    img, draw = create_base_canvas()
    draw.text((70, 140), "Real-Time Ledger & Splits", font=get_font(64, bold=True), fill=(255, 255, 255))
    draw.text((70, 225), "Instant peer synchronization with itemized split breakdowns", font=get_font(34), fill=(148, 163, 184))
    draw.text((70, 275), "and real-time decentralized relay feeds.", font=get_font(34), fill=(148, 163, 184))
    
    sx, sy, sw, sh = draw_phone_frame(draw, 90, 370, 900, 1960)
    
    draw.text((sx + 36, sy + 30), "<  Goa Beach Trip", font=get_font(38, bold=True), fill=(255, 255, 255))
    draw.rounded_rectangle([sx + sw - 190, sy + 30, sx + sw - 115, sy + 76], radius=16, fill=(30, 41, 59))
    draw.text((sx + sw - 175, sy + 38), "INVITE", font=get_font(18, bold=True), fill=(56, 189, 248))
    draw.rounded_rectangle([sx + sw - 95, sy + 30, sx + sw - 32, sy + 76], radius=16, fill=(30, 41, 59))
    draw.text((sx + sw - 82, sy + 38), "AUDIT", font=get_font(18, bold=True), fill=(139, 92, 246))
    
    tab_y = sy + 105
    draw.text((sx + 60, tab_y), "Expenses (14)", font=get_font(30, bold=True), fill=(16, 185, 129))
    draw.line([(sx + 50, tab_y + 44), (sx + 245, tab_y + 44)], fill=(16, 185, 129), width=4)
    draw.text((sx + 320, tab_y), "Balances & Debts", font=get_font(30), fill=(148, 163, 184))
    draw.text((sx + 620, tab_y), "Members (4)", font=get_font(30), fill=(148, 163, 184))
    draw.line([(sx + 32, tab_y + 44), (sx + sw - 32, tab_y + 44)], fill=(45, 58, 78), width=1)
    
    expenses = [
        ("Beach Resort Stay", "Paid by You for all", "₹8,400", "+ ₹6,300", (16, 185, 129), "Oct 15 • Hotel"),
        ("Seafood Dinner", "Paid by Alice", "₹3,200", "- ₹800", (244, 63, 94), "Oct 15 • Food"),
        ("Scuba Diving & Kayak", "Paid by You for all", "₹6,000", "+ ₹4,500", (16, 185, 129), "Oct 16 • Activities"),
        ("Airport Taxi Fare", "Paid by Bob", "₹1,800", "- ₹450", (244, 63, 94), "Oct 16 • Travel"),
        ("Groceries & Snacks", "Paid by Charlie", "₹1,200", "- ₹300", (244, 63, 94), "Oct 16 • Groceries")
    ]
    
    ey = tab_y + 70
    for title, payer, total, share, col, sub in expenses:
        draw.rounded_rectangle([sx + 32, ey, sx + sw - 32, ey + 140], radius=22, fill=(24, 33, 50, 255), outline=(45, 58, 78, 255), width=2)
        draw.text((sx + 54, ey + 26), title, font=get_font(34, bold=True), fill=(255, 255, 255))
        draw.text((sx + 54, ey + 75), f"{payer} • {sub}", font=get_font(24), fill=(148, 163, 184))
        draw.text((sx + sw - 230, ey + 26), share, font=get_font(34, bold=True), fill=col)
        draw.text((sx + sw - 230, ey + 75), f"total {total}", font=get_font(24), fill=(100, 116, 139))
        ey += 160
        
    btn_y = sy + sh - 150
    draw.rounded_rectangle([sx + 40, btn_y, sx + sw - 40, btn_y + 85], radius=24, fill=(16, 185, 129))
    draw.text((sx + sw // 2 - 130, btn_y + 22), "Settle Up Balances", font=get_font(32, bold=True), fill=(255, 255, 255))
    return img

# SCREENSHOT 3: Blockchain Audit & Explorer
def render_screen_3():
    img, draw = create_base_canvas()
    draw.text((70, 140), "Tamper-Proof Ledger", font=get_font(64, bold=True), fill=(255, 255, 255))
    draw.text((70, 225), "Every transaction is an immutable block secured with", font=get_font(34), fill=(148, 163, 184))
    draw.text((70, 275), "Ed25519 digital signatures and SHA-256 hash chains.", font=get_font(34), fill=(148, 163, 184))
    
    sx, sy, sw, sh = draw_phone_frame(draw, 90, 370, 900, 1960)
    
    draw.text((sx + 36, sy + 30), "<  Ledger Blockchain Audit", font=get_font(38, bold=True), fill=(255, 255, 255))
    
    draw.rounded_rectangle([sx + 32, sy + 100, sx + sw - 32, sy + 210], radius=22, fill=(16, 185, 129, 30), outline=(16, 185, 129), width=2)
    draw.text((sx + 60, sy + 125), "[OK] LEDGER INTEGRITY VALID", font=get_font(28, bold=True), fill=(16, 185, 129))
    draw.text((sx + 60, sy + 165), "14 blocks verified • 0 tampering detected • 100% consensus", font=get_font(22), fill=(203, 213, 225))
    
    blocks = [
        ("Block #14 - Expense", "b4f8a92c...e13d7", "Creator: 8f4a...10bc (Alice)", "SHA-256 Valid", "Ed25519 OK"),
        ("Block #13 - Settlement", "7a19c43e...991ab", "Creator: 3d12...49ff (Bob)", "SHA-256 Valid", "Ed25519 OK"),
        ("Block #12 - Expense", "f083d1ba...c882e", "Creator: 8f4a...10bc (You)", "SHA-256 Valid", "Ed25519 OK"),
        ("Block #11 - Member Joined", "31e98bb4...116dc", "Creator: 5b6c...90de (Charlie)", "SHA-256 Valid", "Ed25519 OK"),
        ("Block #0 - Genesis Block", "00000000...genesis", "Creator: 8f4a...10bc (Group Owner)", "Chain Root", "E2EE Key")
    ]
    
    by = sy + 235
    for title, hsh, creator, v1, v2 in blocks:
        draw.rounded_rectangle([sx + 32, by, sx + sw - 32, by + 165], radius=20, fill=(24, 33, 50, 255), outline=(45, 58, 78, 255), width=2)
        draw.text((sx + 54, by + 22), title, font=get_font(30, bold=True), fill=(255, 255, 255))
        draw.text((sx + 54, by + 68), f"Hash: {hsh}", font=get_font(24), fill=(56, 189, 248))
        draw.text((sx + 54, by + 105), creator, font=get_font(22), fill=(148, 163, 184))
        
        draw.rounded_rectangle([sx + sw - 230, by + 22, sx + sw - 54, by + 62], radius=12, fill=(16, 185, 129, 40))
        draw.text((sx + sw - 215, by + 30), v1, font=get_font(20, bold=True), fill=(16, 185, 129))
        draw.rounded_rectangle([sx + sw - 210, by + 75, sx + sw - 54, by + 115], radius=12, fill=(139, 92, 246, 40))
        draw.text((sx + sw - 195, by + 83), v2, font=get_font(20, bold=True), fill=(139, 92, 246))
        by += 185
    return img

# SCREENSHOT 4: Min-Cash-Flow Settling & UPI
def render_screen_4():
    img, draw = create_base_canvas()
    draw.text((70, 140), "Smart Debt Simplification", font=get_font(64, bold=True), fill=(255, 255, 255))
    draw.text((70, 225), "Greedy graph algorithm minimizes total payments.", font=get_font(34), fill=(148, 163, 184))
    draw.text((70, 275), "1-click UPI payments and instant payment reminders.", font=get_font(34), fill=(148, 163, 184))
    
    sx, sy, sw, sh = draw_phone_frame(draw, 90, 370, 900, 1960)
    
    draw.text((sx + 36, sy + 30), "<  Simplified Debts", font=get_font(38, bold=True), fill=(255, 255, 255))
    
    draw.rounded_rectangle([sx + 32, sy + 100, sx + sw - 32, sy + 210], radius=22, fill=(14, 165, 233, 25), outline=(14, 165, 233), width=2)
    draw.text((sx + 60, sy + 125), "SMART GRAPH: 12 TRANSACTIONS -> 3", font=get_font(26, bold=True), fill=(56, 189, 248))
    draw.text((sx + 60, sy + 165), "Min-Cash-Flow algorithm squares up everyone with minimal transfers.", font=get_font(22), fill=(203, 213, 225))
    
    debts = [
        ("Alice pays You", "₹2,100", True, False),
        ("Bob pays You", "₹1,100", True, False),
        ("You pay Charlie", "₹750", False, True),
    ]
    
    dy = sy + 235
    for desc, amt, can_remind, can_pay in debts:
        draw.rounded_rectangle([sx + 32, dy, sx + sw - 32, dy + 160], radius=22, fill=(24, 33, 50, 255), outline=(45, 58, 78, 255), width=2)
        draw.text((sx + 54, dy + 32), desc, font=get_font(34, bold=True), fill=(255, 255, 255))
        draw.text((sx + 54, dy + 88), "via UPI / Cash consensus", font=get_font(24), fill=(148, 163, 184))
        draw.text((sx + sw - 330, dy + 32), amt, font=get_font(38, bold=True), fill=(16, 185, 129) if can_remind else (244, 63, 94))
        
        if can_remind:
            draw.rounded_rectangle([sx + sw - 160, dy + 35, sx + sw - 54, dy + 105], radius=16, fill=(30, 41, 59), outline=(56, 189, 248), width=1)
            draw.text((sx + sw - 145, dy + 52), "Remind", font=get_font(22, bold=True), fill=(56, 189, 248))
        elif can_pay:
            draw.rounded_rectangle([sx + sw - 140, dy + 35, sx + sw - 54, dy + 105], radius=16, fill=(16, 185, 129))
            draw.text((sx + sw - 122, dy + 52), "Pay", font=get_font(24, bold=True), fill=(255, 255, 255))
        dy += 180
        
    qr_y = dy + 20
    draw.rounded_rectangle([sx + 32, qr_y, sx + sw - 32, qr_y + 400], radius=24, fill=(30, 41, 59, 255), outline=(51, 65, 85, 255), width=2)
    draw.text((sx + sw // 2 - 160, qr_y + 35), "Instant UPI QR Settle-Up", font=get_font(30, bold=True), fill=(255, 255, 255))
    qbx = sx + sw // 2 - 90
    qby = qr_y + 90
    draw.rounded_rectangle([qbx, qby, qbx + 180, qby + 180], radius=16, fill=(255, 255, 255))
    draw.rectangle([qbx + 20, qby + 20, qbx + 60, qby + 60], fill=(0, 0, 0))
    draw.rectangle([qbx + 120, qby + 20, qbx + 160, qby + 60], fill=(0, 0, 0))
    draw.rectangle([qbx + 20, qby + 120, qbx + 60, qby + 160], fill=(0, 0, 0))
    draw.rectangle([qbx + 80, qby + 80, qbx + 100, qby + 100], fill=(0, 0, 0))
    draw.text((sx + sw // 2 - 140, qr_y + 295), "Scan with GPay, PhonePe, Paytm", font=get_font(22), fill=(148, 163, 184))
    draw.text((sx + sw // 2 - 160, qr_y + 335), "Automated E2EE Settlement Block", font=get_font(22, bold=True), fill=(16, 185, 129))
    return img

print("Rendering 4 Google Play screenshots at 1080x2400...")
s1 = render_screen_1()
s2 = render_screen_2()
s3 = render_screen_3()
s4 = render_screen_4()

out_dirs = [
    r'l:\projectz\splitwise\play_store_assets',
    r'C:\Users\RAJES\Desktop\PeerSplit_Play_Store_Assets'
]

for d in out_dirs:
    s1.save(os.path.join(d, '01_dashboard.png'), 'PNG')
    s2.save(os.path.join(d, '02_group_ledger.png'), 'PNG')
    s3.save(os.path.join(d, '03_blockchain_audit.png'), 'PNG')
    s4.save(os.path.join(d, '04_simplified_debts_upi.png'), 'PNG')

print("All screenshots generated and saved cleanly.")
