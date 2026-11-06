{style}

아래 작성 규칙과 JSON 스키마만 보고 사건 하나를 처음부터 쓴다. 다른 사건 파일은 본 적이 없다.

# 작성 규칙

{rules}

# case.json 스키마

```json
{case_schema}
```

# 용의자 파일 스키마

```json
{suspect_schema}
```

# 답하는 형식

JSON 객체 하나를 ```json 블록 하나에 담아 답한다. 모양은 {{"case": case.json 내용, "suspects": [용의자 파일 내용, ...]}}이다. "$schema" 칸은 쓰지 않고, 사건 id는 요청에 적힌 것을 그대로 쓴다. 용의자 파일의 id는 case.json 등장인물의 id와 같아야 한다.
