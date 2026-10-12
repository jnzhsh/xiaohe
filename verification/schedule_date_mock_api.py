"""Loopback date-context fixture. No API credentials, personal data or request logging."""
import argparse
import json
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

ERRORS = {'fixture_model_required', 'text_only_required', 'fixture_question_required', 'original_calendar_not_explicit', 'query_range_not_anchored', 'history_calendar_not_explicit', 'out_of_range_candidate', 'undated_candidate_not_marked', 'dated_fixture_not_recalled', 'credentials_are_not_used_in_this_fixture', 'fixture_request_size'}


def response_for(body):
    if body.get('model') != 'schedule-date-fixture':
        raise ValueError('fixture_model_required')
    content = body['messages'][-1]['content']
    if not isinstance(content, str):
        raise ValueError('text_only_required')
    context = json.loads(content)
    if context.get('本次用户原话') != '明天的咖啡资料有哪些？':
        raise ValueError('fixture_question_required')
    anchor = context['本次原始时间锚']
    query = context['查询日期范围']
    try:
        local = datetime.fromisoformat(anchor['localDateTime'])
        original_clock = local.strftime('%Y-%m-%dT%H:%M') == '2033-03-28T23:45' and local.second == 0 and local.utcoffset().total_seconds() == -7 * 3600
    except (ValueError, TypeError, AttributeError):
        original_clock = False
    if anchor['date'] != '2033-03-28' or context['本次原时日期'] != '2033-03-28' or anchor['zone'] != 'America/Los_Angeles' or not original_clock:
        raise ValueError('original_calendar_not_explicit')
    if query['date'] != '2033-03-29' or query['sourceCalendarDate'] != '2033-03-28' or query['zone'] != anchor['zone'] or query['end'] <= query['start']:
        raise ValueError('query_range_not_anchored')
    for message in context['近期原始对话']:
        if 'sourceCalendar' not in message or not message['sourceCalendar'].get('date'):
            raise ValueError('history_calendar_not_explicit')
    matches = []
    for memory in context['候选个人记忆']:
        time = memory.get('time', {})
        if memory.get('queryTimeBasis') == 'date_known':
            time = memory.get('scheduleTime', {})
            if time.get('state') != 'date_known' or not time.get('clockUncertain'):
                raise ValueError('undated_candidate_not_marked')
        if time.get('state') in ('resolved', 'date_known'):
            if time['start'] >= query['end'] or time.get('end', time['start'] + 1) <= query['start']:
                raise ValueError('out_of_range_candidate')
        elif memory.get('queryTimeBasis') != 'undated_background':
            raise ValueError('undated_candidate_not_marked')
        if memory.get('title') == '咖啡资料：日期范围内fixture':
            matches.append(memory)
    if len(matches) != 1:
        raise ValueError('dated_fixture_not_recalled')
    return {'choices': [{'message': {'role': 'assistant', 'content': json.dumps({'intent': 'query', 'reply': '2033年3月29日的咖啡资料，我找到这一份日期范围内的记录。', 'memories': [], 'updates': [], 'evidenceIds': [matches[0]['id'], 'not-a-candidate-fixture']}, ensure_ascii=False)}}]}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            if self.headers.get('Authorization'):
                raise ValueError('credentials_are_not_used_in_this_fixture')
            size = int(self.headers.get('Content-Length', '0'))
            if size <= 0 or size > 8 * 1024 * 1024:
                raise ValueError('fixture_request_size')
            result = response_for(json.loads(self.rfile.read(size)))
            data = json.dumps(result, ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (ValueError, KeyError, TypeError) as error:
            reason = str(error)
            self.send_error(400, reason if reason in ERRORS else 'fixture_contract_failed')
    def log_message(self, *args):
        pass


def self_test():
    start = int(datetime(2033, 3, 29, tzinfo=ZoneInfo('America/Los_Angeles')).timestamp() * 1000)
    end = int(datetime(2033, 3, 30, tzinfo=ZoneInfo('America/Los_Angeles')).timestamp() * 1000)
    context = {'本次用户原话': '明天的咖啡资料有哪些？', '本次原始时间锚': {'date': '2033-03-28', 'zone': 'America/Los_Angeles', 'localDateTime': '2033-03-28T23:45:00-07:00'}, '本次原时日期': '2033-03-28', '查询日期范围': {'date': '2033-03-29', 'sourceCalendarDate': '2033-03-28', 'zone': 'America/Los_Angeles', 'start': start, 'end': end}, '近期原始对话': [{'sourceCalendar': {'date': '2033-03-28'}}], '候选个人记忆': [{'id': 'fixture-record-0001', 'title': '咖啡资料：日期范围内fixture', 'time': {'state': 'resolved', 'start': start + 3600000, 'end': start + 7200000}}]}
    body = {'model': 'schedule-date-fixture', 'messages': [{'role': 'user', 'content': json.dumps(context, ensure_ascii=False)}]}
    assert json.loads(response_for(body)['choices'][0]['message']['content'])['intent'] == 'query'
    context['本次原始时间锚']['localDateTime'] = '2033-03-28T23:45-07:00'
    body['messages'][0]['content'] = json.dumps(context, ensure_ascii=False)
    assert json.loads(response_for(body)['choices'][0]['message']['content'])['intent'] == 'query'
    context['候选个人记忆'][0]['time']['start'] = end + 1
    body['messages'][0]['content'] = json.dumps(context, ensure_ascii=False)
    try:
        response_for(body)
    except ValueError:
        pass
    else:
        raise AssertionError('out_of_range_fixture_accepted')
    print(json.dumps({'status': 'passed', 'verified': 'explicit original local date; anchored tomorrow; range rejection; no credentials or logging'}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8902)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
