import os

def save(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8') as f:
        f.write(s.strip() + '\n')
    print('Wrote:', p)
