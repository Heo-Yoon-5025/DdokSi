package com.ddoksi.ddoksi.bill.analysis;

import com.ddoksi.ddoksi.bill.entity.Bill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 법안 분석 프롬프트.
 *
 * <p>프롬프트와 출력 스키마, 입력 해시를 한 곳에 모아 둔다. 셋은 함께 바뀌는 것들이다 —
 * 어느 하나를 고치면 결과가 달라지므로 {@code prompt_version} 도 함께 올려야 한다.
 *
 * <p><b>비용 구조가 이 클래스의 형태를 결정했다.</b> 출력 토큰이 입력의 5배 단가이고
 * 19,406건을 처리하므로, 전체 비용의 90% 이상이 "모델이 몇 글자를 쓰는가" 로 정해진다.
 * 그래서 항목별 글자 수 상한을 말로만 지시하지 않고 완성 예시를 하나 박아 넣었다.
 * 예시가 길어져 캐시 읽기 토큰이 늘지만 그쪽은 정가의 0.1배라, 비싼 쪽을 싼 쪽으로 누르는 거래다.
 */
public final class BillAnalysisPrompt {

    private BillAnalysisPrompt() {
    }

    /**
     * 프롬프트 버전. 시스템 프롬프트·출력 스키마·사용자 메시지 형식 중 무엇이든 바뀌면 올린다.
     *
     * <p>설정으로 빼지 않고 상수로 둔 이유: 이 값은 운영자가 조정하는 손잡이가 아니라
     * 아래 프롬프트 내용과 한 몸이다. 설정으로 두면 코드와 버전이 어긋난 채로 돌 수 있다.
     * 어느 버전을 생성할지·조회할지는 설정이 정하지만, 그 값이 가리키는 실체는 여기다.
     */
    public static final String VERSION = "v1";

    /**
     * 캐시되는 고정 프리픽스.
     *
     * <p>19,406번 완전히 같은 바이트여야 캐시가 걸린다. 여기에 날짜나 법안별 정보가
     * 단 한 글자라도 섞이면 매 호출이 캐시 미스가 되고, 그것은 조용히 일어난다.
     * 법안별 내용은 전부 {@link #userMessage(Bill, String)} 쪽에 있다.
     *
     * <p>Opus 5 의 캐시 최소 길이는 512 토큰이다. 이 프리픽스는 그보다 충분히 길다.
     */
    public static final String SYSTEM = """
            당신은 대한민국 국회에 발의된 법률안을 일반 시민에게 설명하는 편집자다.

            # 입력에 대해 알아야 할 것

            '제안이유 및 주요내용'은 법안을 발의한 의원이 직접 쓴 글이다. 중립적 해설이 아니라
            법안의 필요성을 주장하는 문서다. 거기 적힌 효과나 문제 진단은 발의자의 주장이므로
            사실로 단정하지 말고, 법안이 제도를 어떻게 바꾸는지를 중심으로 서술한다.

            본문에 없는 사실을 만들지 않는다. 실존 인물·단체·여론을 인용하지 않는다.
            근거가 없으면 해당 항목을 비운다.

            # 출력 항목

            ## hook (40자 이내, 한 문장)
            SNS 게시물 첫 줄이나 카드뉴스 표지로 그대로 쓸 문장. 뉴스 헤드라인 문체로 쓴다.
            물음표로 끝내지 않는다. 문장 끝에 마침표를 찍지 않는다.

            ## summary (150자 이내)
            이 법안이 제도를 무엇에서 무엇으로 바꾸는지.

            ## example (120자 이내)
            이 법은 누구의 어떤 상황과 닿아 있는가.
            바뀌는 것이 있으면 무엇이 달라지는지 쓰고, 기한 연장이나 현행 유지처럼 달라지는 것이
            없으면 "그대로 유지된다"고 그대로 쓴다. 없는 변화를 만들지 않는다.
            닿는 대상을 특정할 수 없으면 비운다.

            ## background (150자 이내)
            왜 지금 이 법안이 나왔는지. 본문이 지적한 현행 제도의 문제를 쓴다.

            ## topics (0~2개)
            아래 어휘에서만 고른다. 맞는 것이 없으면 빈 배열로 둔다.
            %s

            # 문장 규칙

            ## 1. 주제어 자립
            각 항목은 단독으로 잘라내어 카드 한 장에 올려도 무슨 이야기인지 알 수 있어야 한다.
            법안명("도로교통법 일부개정법률안")을 쓰지 말고 내용 핵심어("어린이통학버스")를 써서
            스스로 주제를 밝힌다. 법안명은 화면 상단에 이미 표시되므로 반복하지 않는다.

            ## 2. 각도 분리
            네 항목이 같은 단어를 반복하면 이어 읽을 때 지루해진다. 항목마다 다른 각도에서
            주제를 잡는다.
              hook        바뀌는 것
              summary     제도
              example     사람
              background  문제

            ## 3. 연결어 금지
            다른 항목을 가리키는 표현을 쓰지 않는다.
            금지: 이에 따라 / 앞서 / 위와 같이 / 이러한 / 이 법안은(앞 문장을 받는 경우)
            각 항목은 앞뒤 항목을 읽지 않은 사람을 대상으로 쓴다.

            ## 4. 문체
            평서형 종결(~다). 존댓말과 감탄사를 쓰지 않는다.
            법률 용어가 불가피하면 괄호로 짧게 풀어 쓴다.

            # 예시

            입력:
              법안명: 도로교통법 일부개정법률안
              소관위원회: 행정안전위원회
              제안이유 및 주요내용:
                현행법은 버스정류장 10미터 이내에서 모든 차량의 정류를 금지하고 있어,
                어린이통학버스가 버스정류장을 승하차 장소로 활용할 수 없는 관계로 어린이들이
                차도에서 승하차를 하면서 사고 위험에 노출되고, 통학버스가 갓길에 정차함으로써
                도로교통 흐름을 방해하는 문제가 발생하고 있음.
                이에 버스정류장 내에서 어린이통학버스의 정차를 허용하여 어린이의 안전을 확보하고,
                도로교통의 원활한 흐름을 도모하려는 것임(안 제32조제4호 단서).

            출력:
            {
              "hook": "어린이통학버스, 버스정류장에 설 수 있게 된다",
              "summary": "버스정류장 10미터 안에서는 모든 차량의 정차가 금지돼 있다. 이 규제에 예외를 두어 통학버스만은 정류장 안에 설 수 있도록 허용하는 내용이다.",
              "example": "통학버스로 등하교하는 아이들은 지금 차도에 내려 인도까지 걸어야 한다. 법이 바뀌면 정류장 안에서 바로 타고 내릴 수 있다.",
              "background": "정류장에 설 수 없는 통학버스는 갓길에 멈춰 왔다. 차도 승하차로 아이들이 사고 위험에 노출되고 갓길 정차가 교통 흐름을 막는다는 지적이 이어졌다.",
              "topics": ["교통·안전", "교육·보육"]
            }
            """.formatted(String.join(" / ", BillTopic.labels()));

    /**
     * 법안별로 바뀌는 부분.
     *
     * <p><b>상태(status, procResultRaw)와 발의자, 날짜를 일부러 뺐다.</b> 이유가 각각 다르다.
     * 상태를 넣으면 {@link #sourceHash(Bill, String)} 가 상태 변화마다 바뀌어 법안이
     * 통과·폐기될 때마다 전량 재생성이 걸린다. 그리고 "이 법은 통과됐다" 를 알려주면
     * 분석이 결과 쪽으로 물든다. 발의자를 넣으면 정당 편향이 들어올 여지가 생긴다.
     */
    public static String userMessage(Bill bill, String billText) {
        return """
                법안명: %s
                소관위원회: %s
                제안이유 및 주요내용:
                %s
                """.formatted(
                bill.getTitle(),
                Objects.toString(bill.getCommitteeName(), "(없음)"),
                billText);
    }

    /**
     * 분석 입력의 해시. 이 값이 달라지면 재생성 대상이다.
     *
     * <p>모델에게 실제로 보낸 것만 넣는다 — {@link #userMessage} 와 같은 재료다.
     * 시스템 프롬프트는 여기 들어가지 않는다. 그쪽 변경은 {@link #VERSION} 이 담당하고,
     * 둘을 섞으면 프롬프트를 고칠 때마다 19,406건의 해시가 전부 바뀌어 두 장치가 겹친다.
     */
    public static String sourceHash(Bill bill, String billText) {
        String input = bill.getTitle() + "\n"
                + Objects.toString(bill.getCommitteeName(), "") + "\n"
                + billText;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 에 있어야 하는 알고리즘이다. 없다면 환경이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    /**
     * 출력 JSON 스키마.
     *
     * <p>모든 문자열이 {@code ["string", "null"]} 인 것은 의도적이다. "근거가 없으면 비운다"
     * 를 스키마가 허용하지 않으면 모델은 빈칸을 만들 수 없어 무언가를 지어낸다.
     *
     * <p>{@code topics} 에 {@code enum} 을 걸지 않았다. 한국어 값이 든 enum 스키마가
     * SDK·모델 조합에서 동일하게 동작하는지 확인하지 못했고, 실패하면 전량 호출이
     * 런타임에 깨진다. 어휘 강제는 프롬프트와 {@link BillTopic#sanitize(List)} 가 맡는다.
     * 파일럿에서 어휘 밖 값이 실제로 나오는지 보고 필요하면 enum 을 추가한다.
     */
    public static Map<String, Object> outputSchema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("hook", nullableString);
        properties.put("summary", nullableString);
        properties.put("example", nullableString);
        properties.put("background", nullableString);
        properties.put("topics", Map.of(
                "type", "array",
                "maxItems", BillTopic.MAX_PER_BILL,
                "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("hook", "summary", "example", "background", "topics"));
        schema.put("properties", properties);
        return schema;
    }
}
