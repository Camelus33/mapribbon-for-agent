# Renders the title/closing cards and the caption bands for the demo video (1920x1080, Korean text).
from PIL import Image, ImageDraw, ImageFont
import os

OUT = os.path.join(os.path.dirname(__file__), '../../out/video/cap')
os.makedirs(OUT, exist_ok=True)
FONT = '/System/Library/Fonts/AppleSDGothicNeo.ttc'
REG, MED, BOLD = 0, 2, 6
W, H = 1920, 1080
BG = (15, 21, 19)
INK = (232, 239, 236)
DIM = (143, 161, 154)
TEAL = (92, 195, 224)
RED = (239, 106, 91)
SIM = (185, 140, 240)


def font(size, idx):
    return ImageFont.truetype(FONT, size, index=idx)


def caption(name, text, sub=None):
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Always the same 150px band under the app frame, so captions never cover the screen they describe.
    band = 150
    d.rectangle([0, H - band, W, H], fill=(26, 26, 25, 255))
    d.rectangle([0, H - band, 8, H], fill=INK + (255,))
    top = H - band + (28 if sub else 50)
    d.text((56, top), text, font=font(44, BOLD), fill=INK)
    if sub:
        d.text((56, top + 62), sub, font=font(30, REG), fill=DIM)
    img.save(os.path.join(OUT, f'{name}.png'))


def card(name, lines):
    img = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(img)
    y = 300
    for text, size, idx, color, gap in lines:
        d.text((150, y), text, font=font(size, idx), fill=color)
        y += gap
    d.rectangle([150, 250, 270, 258], fill=RED)
    img.save(os.path.join(OUT, f'{name}.png'))


card('title', [
    ('맵톡 드라이브 리본', 110, BOLD, INK, 150),
    ('앞서 지나간 차들이 도로 시설물마다 남긴 기록을,', 48, MED, INK, 66),
    ('경로를 정하는 순간 한 장의 화면으로 보여 준다.', 48, MED, INK, 150),
    ('Tedin · 현대자동차그룹 차량용 앱 개발 공모전', 34, REG, DIM, 52),
    ('Pleos Connect 에뮬레이터 v2.1.0 · 차량 통과 기록과 평소값은 시뮬레이션', 34, REG, SIM, 0),
])
card('closing', [
    ('리본 규격 maptalk.ribbon/1', 72, BOLD, INK, 130),
    ('· 서로 다른 차 3대 미만이면 공개하지 않는다', 42, MED, INK, 64),
    ('· 계정·차대번호 없음, 하루마다 바뀌는 익명 토큰만', 42, MED, INK, 64),
    ('· 날씨는 맥락, 한 지점에서만 달라질 때 "평소와 다름"', 42, MED, INK, 64),
    ('· 에뮬레이터 1배속 주행 CPU 약 2% · RSS 약 160MB', 42, MED, INK, 130),
    ('Tedin Inc. · 맵톡 드라이브', 36, REG, DIM, 0),
])

caption('c_empty', '시연 구간: 서울양양고속도로 인제IC → 양양JC 34.5 km', '실제 도로 모양을 출발(왼쪽 아래)에서 도착(오른쪽 위)으로 · 터널 14, 분기점 2, 단속카메라 2(가상) · 아직 기록 없음')
caption('c_fill1', '가상 차량 9대가 지나가며 시설물마다 기록을 남긴다')
caption('c_fill2', '평소값과 이웃 시설물보다 높은 곳만 표시된다', '인제양양터널 출구 와이퍼 켠 차 9대 중 6대 (평소 3%) · 서양양IC 늦은 끼어들기 9대 중 4대 (평소 8%)')
caption('c_ctrl1', '대조군: 같은 9대가 처음부터 끝까지 비 속을 달린다')
caption('c_ctrl2', '와이퍼 사용 100%, 그래도 평소와 다른 곳 0곳', '도로 전체의 날씨는 한 지점의 이상이 아니다')
caption('c_drive1', '주행 중에는 다음 한 곳까지의 거리와 무엇이 다른지만', '시뮬레이션 주행 20배속 · 지나온 길은 흐리게, 다음 지점까지는 진하게')
caption('c_drive2', '터널 안: GPS가 끊겨도 출구까지 남은 거리를 센다')
caption('c_drive3', '지나간 뒤에는 남은 경로만 본다', '남은 길에 평소와 다른 곳이 없으면 그렇게 말한다 · 기록 없는 곳을 안전하다고 하지 않는다')
caption('c_signals', '실제 Pleos Vehicle SDK 신호', 'Virtual Console에서 앞 와이퍼를 켜자 앱의 차량 연결 칸이 바로 받는다')
caption('c_restrict', '기어 D → CarUxRestrictions → 버튼 없는 주행 화면으로 자동 전환', '왼쪽 계기판 기어 표시와 함께 확인')
print('cards ok')
