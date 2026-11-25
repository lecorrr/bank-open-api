# mask.rb: Logstash's ruby filter for the bank's logs. It masks personal data in every field of an event before
# Elasticsearch stores it:
#   - IBANs, wherever they appear: the country code and the last four characters stay, as on a bank statement;
#   - sign-in identities and people's names (username, Keycloak's user id, email, first, last and full name, a
#     payment's creditor or debtor): a pseudonym, the same for the same value, so one user's events still line up;
#   - the values PostgreSQL quotes in its error details (a duplicate key, a failing row) and passwords in SQL or in
#     key=value text: removed.
# A value that is a whole JSON document, such as a log line kept as it came, is masked field by field like the event,
# its keys as text; other text by the patterns below, which also read JSON text, with its escaped quotes and dotted
# keys.
# IP addresses and the bank's own ids (customer number, consent and payment ids) stay: support follows a case by
# them. Logstash runs the tests at the end of this file when it loads the pipeline and refuses to start if one fails.
require "json"
require "openssl"

# An IBAN in its electronic form or printed in groups of four. Lowercase ids, such as trace ids, never match.
IBAN = /\b[A-Z]{2}\d{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?)\b/

# A field whose name contains one of these (its path joined, lowercase, letters and digits only) names a person.
IDENTITY_FIELDS = %w[username userid email firstname lastname fullname creditorname debtorname ownername
                     ultimatecreditor ultimatedebtor].freeze
SECRET_FIELDS = %w[password].freeze

# The same in text: key="value" as Keycloak writes its events, "key":"value" of JSON in a message, and key=value.
# A key may follow a prefix (Keycloak's previous_email, customer.email, DB_PASSWORD), be dotted (user.name) and have
# its quotes escaped (\"), as in JSON text inside a string.
KEY_START = "(?<![A-Za-z0-9])"
IDENTITY_KEY = "(?:user[._]?name|user[._]?id|e[._]?mail|first[._]?name|last[._]?name|full[._]?name|" \
               "creditor[._]?name|debtor[._]?name|owner[._]?name|ultimate[._]?creditor|ultimate[._]?debtor)"
QUOTE = '\\\\?"'
# A quoted value runs to the next quote, escaped or not.
QUOTED_VALUE = '(?:[^"\\\\]|\\\\(?!"))*'
QUOTED_IDENTITY = /#{KEY_START}(#{IDENTITY_KEY})(=#{QUOTE}|#{QUOTE}\s*:\s*#{QUOTE})(#{QUOTED_VALUE})/i
BARE_IDENTITY = /#{KEY_START}(#{IDENTITY_KEY})(=)(?!#{QUOTE})([^"\s,;&)]+)/i
QUOTED_SECRET = /#{KEY_START}(password)(=#{QUOTE}|#{QUOTE}\s*:\s*#{QUOTE})(#{QUOTED_VALUE})/i
BARE_SECRET = /#{KEY_START}(password)(=)(?!#{QUOTE})([^"\s,;&)]+)/i
SQL_PASSWORD = /\b(PASSWORD\s+)'(?:[^']|'')*'/i

# PostgreSQL quotes the values of a duplicate or missing key, Key (iban)=(UA...) already exists, and the whole row
# that broke a rule, Failing row contains (...).
KEY_ENDINGS = "already exists|is not present in table|is still referenced from table|conflicts with existing key"
DATABASE_KEY = /(\bKey \(.*?\)=\().*?(\) (?:#{KEY_ENDINGS}))/
FAILING_ROW = /(\bFailing row contains \().*(\))/

MASKED = "masked"
PSEUDONYM = /\Amasked-[0-9a-f]{12}\z/
JSON_DOCUMENT = /\A\s*[\[{]/

def register(params)
  @key = params["pseudonym_key"].to_s
  # With a short key the pseudonyms of guessable values, such as sign-in names, could be worked back.
  raise ArgumentError, "pseudonym_key must have 32 characters at least" if @key.length < 32
end

def filter(event)
  event.to_hash.each do |field, value|
    masked = mask(value, field_name(field))
    event.set("[#{field}]", masked) unless masked == value
  end
  [event]
end

# Masks what is personal in a value; name is the value's field path, as IDENTITY_FIELDS spell it.
def mask(value, name)
  case value
  when Hash then value.to_h { |key, child| [key, mask(child, name + field_name(key))] }
  when Array then value.map { |item| mask(item, name) }
  when String, Numeric then mask_value(value, name)
  else value
  end
end

def field_name(key)
  key.to_s.downcase.gsub(/[^a-z0-9]/, "")
end

def mask_value(value, name)
  if IDENTITY_FIELDS.any? { |field| name.include?(field) }
    pseudonym(value.to_s)
  elsif SECRET_FIELDS.any? { |field| name.include?(field) }
    MASKED
  elsif value.is_a?(String)
    # Bytes that are not UTF-8 become "?": the patterns cannot read text that holds them.
    text = value.scrub("?")
    mask_json(text, name) || mask_text(text)
  else
    value
  end
end

# A text that is a whole JSON object or array is masked as one: parsed, masked field by field, its keys as text, and
# written back if anything changed, so a nested {"user": {"name": ...}} is found as a field would be. The text
# patterns, which every string in it has been through, do not run over the document as a whole: a pattern that runs
# to the end of a line, as a failing row's does, would take the fields after it. nil for text that is not JSON.
def mask_json(text, name)
  return nil unless text.match?(JSON_DOCUMENT)

  document = JSON.parse(text)
  masked = mask_keys(mask(document, name))
  masked == document ? text : JSON.generate(masked)
rescue JSON::NestingError, JSON::GeneratorError
  # Nested too deep to read field by field, or a number JSON cannot write back (1e400): the value is not stored.
  MASKED
rescue JSON::ParserError
  nil
end

# The keys of a JSON document are text too: a service may key a map by IBAN.
def mask_keys(value)
  case value
  when Hash then value.to_h { |key, child| [mask_text(key.to_s), mask_keys(child)] }
  when Array then value.map { |item| mask_keys(item) }
  else value
  end
end

def mask_text(text)
  text = text.gsub(DATABASE_KEY) { "#{$1}#{MASKED}#{$2}" }
  text = text.gsub(FAILING_ROW) { "#{$1}#{MASKED}#{$2}" }
  text = text.gsub(SQL_PASSWORD) { "#{$1}'#{MASKED}'" }
  text = text.gsub(QUOTED_SECRET) { "#{$1}#{$2}#{MASKED}" }
  text = text.gsub(BARE_SECRET) { "#{$1}#{$2}#{MASKED}" }
  text = text.gsub(QUOTED_IDENTITY) { "#{$1}#{$2}#{pseudonym($3)}" }
  text = text.gsub(BARE_IDENTITY) { "#{$1}#{$2}#{pseudonym($3)}" }
  text.gsub(IBAN) { |iban| mask_iban(iban) }
end

# The same value always gives the same pseudonym; without the key nobody can tell whose it is. Keycloak writes
# "null" for a user it does not know, and a pseudonym is not masked again, so an event can pass twice.
def pseudonym(value)
  return value if value.empty? || value == "null" || value.match?(PSEUDONYM)

  "masked-#{OpenSSL::HMAC.hexdigest('SHA256', @key, value)[0, 12]}"
end

# Keeps the country code and the last four characters; the spaces of the printed form stay.
def mask_iban(iban)
  total = iban.count("A-Z0-9")
  seen = 0
  iban.gsub(/[A-Z0-9]/) do |char|
    seen += 1
    seen <= 2 || seen > total - 4 ? char : "*"
  end
end

# ---- tests, run by Logstash when it loads the pipeline (the key is a test value) ----

test "an IBAN keeps its country code and last four characters" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event { { "message" => "transfer from UA213223130000026007233566001 refused" } }
  expect("one event") { |events| events.size == 1 }
  expect("the IBAN masked") do |events|
    events.first.get("message") == "transfer from UA***********************6001 refused"
  end
end

test "an IBAN printed in groups of four keeps its spaces" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event { { "message" => "to UA21 3223 1300 0002 6007 2335 6600 1." } }
  expect("the IBAN masked") { |events| events.first.get("message") == "to UA** **** **** **** **** **** *600 1." }
end

test "every IBAN goes, in any field" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    { "message" => "from UA213223130000026007233566001 to UA573999990000000000000000002",
      "error.stack_trace" => "java.lang.IllegalStateException: UA843999990000000000000000001\n\tat x.Y.z(Y.java:1)",
      "payment" => { "creditor" => { "account" => "DE89370400440532013000" } },
      "tags" => ["UA573999990000000000000000002"] }
  end
  expect("no IBAN left") do |events|
    text = events.first.to_hash.to_s
    %w[3223130000026007233566 3999990000000000000000 37040044053201].none? { |digits| text.include?(digits) }
  end
  expect("the country codes and last digits left") do |events|
    events.first.get("[payment][creditor][account]") == "DE****************3000" &&
      events.first.get("tags") == ["UA***********************0002"]
  end
end

test "lowercase ids, trace ids and words stay as they are" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    { "message" => "Payment 3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f is ACCP, 25.00 UAH, ISO8601, SHA256, TLS13, " \
                   "span ab12f3577b34da6a3ce929d0e0e4736c",
      "trace.id" => "4bf92f3577b34da6a3ce929d0e0e4736", "http.request.id" => "7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a",
      "service.name" => "bank-open-api", "kubernetes" => { "pod" => { "name" => "bank-open-api-6d9f8b7c5-x2k4j" } } }
  end
  expect("nothing changed") do |events|
    event = events.first
    event.get("message").end_with?("ISO8601, SHA256, TLS13, span ab12f3577b34da6a3ce929d0e0e4736c") &&
      event.get("message").start_with?("Payment 3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f is ACCP, 25.00 UAH,") &&
      event.get("[trace.id]") == "4bf92f3577b34da6a3ce929d0e0e4736" &&
      event.get("[http.request.id]") == "7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a" &&
      event.get("[service.name]") == "bank-open-api" &&
      event.get("[kubernetes][pod][name]") == "bank-open-api-6d9f8b7c5-x2k4j"
  end
end

test "Keycloak's event: the user becomes a pseudonym, the rest stays" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    { "message" => 'type="LOGIN_ERROR", realmId="6a9e1b2c", realmName="bank", clientId="smoke-test", ' \
                   'userId="8d4f2a6e-1b3c-4d5e-9f7a-2c4e6a8b0d1f", ipAddress="10.42.0.12", ' \
                   'error="invalid_user_credentials", auth_method="openid-connect", username="artem.demo"' }
  end
  expect("no username or user id left") do |events|
    message = events.first.get("message")
    !message.include?("artem.demo") && !message.include?("8d4f2a6e")
  end
  expect("pseudonyms in their place") do |events|
    events.first.get("message") =~ /userId="masked-[0-9a-f]{12}".*username="masked-[0-9a-f]{12}"\z/
  end
  expect("the pseudonym is HMAC-SHA256 with the key") do |events|
    events.first.get("message").end_with?('username="masked-a14d8efcc6f6"')
  end
  expect("the rest as it was") do |events|
    message = events.first.get("message")
    ['type="LOGIN_ERROR"', 'realmName="bank"', 'clientId="smoke-test"', 'ipAddress="10.42.0.12"',
     'error="invalid_user_credentials"'].all? { |part| message.include?(part) }
  end
end

test "pairs without quotes, and keys after a prefix, as Keycloak's updated_ and previous_ ones" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    [{ "message" => 'type="UPDATE_EMAIL", previous_email="artem@example.com", updated_email="tkachuk@example.com"' },
     { "message" => "sign-in failed for username=artem.demo, email=artem@example.com; first_name=Artem" },
     { "message" => 'export customer_email="olena@example.com" DB_PASSWORD=s3cret' }]
  end
  expect("none left") do |events|
    text = events.map { |e| e.get("message") }.join(" ")
    %w[artem@example.com tkachuk@example.com artem.demo Artem olena@example.com s3cret]
      .none? { |value| text.include?(value) }
  end
  expect("each a pseudonym in its place") do |events|
    events[0].get("message") =~ /previous_email="masked-[0-9a-f]{12}", updated_email="masked-[0-9a-f]{12}"\z/ &&
      events[1].get("message") =~ /\Asign-in failed for username=masked-[0-9a-f]{12}, email=masked-[0-9a-f]{12}; / &&
      events[2].get("message") =~ /\Aexport customer_email="masked-[0-9a-f]{12}" DB_PASSWORD=masked\z/
  end
end

test "the same value gives the same pseudonym, another value another" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    [{ "message" => 'type="LOGIN_ERROR", username="artem.demo"' },
     { "message" => 'type="LOGIN_ERROR", username="olena.k"' },
     { "user.name" => "artem.demo" }]
  end
  expect("one pseudonym for one user") do |events|
    first = events[0].get("message")[/username="([^"]+)"/, 1]
    second = events[1].get("message")[/username="([^"]+)"/, 1]
    first.start_with?("masked-") && first == events[2].get("[user.name]") && first != second
  end
end

test "a pseudonym is not masked again" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event { { "message" => 'username="masked-0a1b2c3d4e5f"', "user.name" => "masked-0a1b2c3d4e5f" } }
  expect("the same pseudonym") do |events|
    events.first.get("message") == 'username="masked-0a1b2c3d4e5f"' &&
      events.first.get("[user.name]") == "masked-0a1b2c3d4e5f"
  end
end

test "a user Keycloak does not know stays null" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event { { "message" => 'type="LOGIN_ERROR", userId="null", error="user_not_found", username="nobody"' } }
  expect("null kept, the name masked") do |events|
    message = events.first.get("message")
    message.include?('userId="null"') && !message.include?("nobody")
  end
end

test "fields that name a person become pseudonyms, wherever they are" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    { "user.name" => "artem.demo", "user" => { "id" => 42, "email" => "artem@example.com" },
      "creditor" => { "name" => "Olena Kovalenko" }, "payment" => { "debtorName" => "Artem Tkachuk" },
      "first_name" => "Olena", "service.name" => "bank-core" }
  end
  expect("each a pseudonym") do |events|
    event = events.first
    ["[user.name]", "[user][id]", "[user][email]", "[creditor][name]", "[payment][debtorName]", "[first_name]"]
      .all? { |field| event.get(field).to_s =~ /\Amasked-[0-9a-f]{12}\z/ }
  end
  expect("other names stay") { |events| events.first.get("[service.name]") == "bank-core" }
end

test "PostgreSQL's details lose the values they quote" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    [{ "message" => "ERROR: duplicate key value violates unique constraint \"account_iban_key\"\n" \
                    "  Detail: Key (iban)=(UA213223130000026007233566001) already exists." },
     { "message" => "DETAIL:  Failing row contains (5c1d, UA213223130000026007233566001, Olena (Lena) K, null)." },
     { "message" => "DETAIL:  Key (account_id)=(42) is not present in table \"account\"." }]
  end
  expect("the values masked") do |events|
    events[0].get("message").end_with?("Detail: Key (iban)=(masked) already exists.") &&
      events[1].get("message") == "DETAIL:  Failing row contains (masked)." &&
      events[2].get("message") == "DETAIL:  Key (account_id)=(masked) is not present in table \"account\"."
  end
end

test "passwords in SQL and in text are removed" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    [{ "message" => "STATEMENT:  ALTER ROLE bank WITH LOGIN PASSWORD 'p4ss''word';" },
     { "message" => "url jdbc:postgresql://postgres/bank?user=bank&password=s3cret&ssl=true" },
     { "message" => 'body {"password":"s3cret"}', "db.password" => "s3cret" }]
  end
  expect("no password left") do |events|
    events[0].get("message") == "STATEMENT:  ALTER ROLE bank WITH LOGIN PASSWORD 'masked';" &&
      events[1].get("message") == "url jdbc:postgresql://postgres/bank?user=bank&password=masked&ssl=true" &&
      events[2].get("message") == 'body {"password":"masked"}' && events[2].get("[db.password]") == "masked"
  end
end

test "a line kept whole as JSON is masked as the event would be" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    line = '{"log.level":"WARN","user.name":"artem.demo","user":{"name":"olena.k","email":"olena@example.com"},' \
           '"message":"transfer from UA213223130000026007233566001 refused for username=\"artem.demo\"; Detail: ' \
           'Key (iban)=(UA213223130000026007233566001) already exists.","payment":{"creditorName":"Olena Kovalenko"},' \
           '"db":{"password":"s3cret"},"UA573999990000000000000000002":1,' \
           '"error.message":"Detail: Failing row contains (5c1d, Olena (Lena) K).",' \
           '"error.stack_trace":"boom\\n\\tat a.B.c(B.java:10)"}'
    [{ "event" => { "original" => line } }, { "message" => '[{"user":{"name":"olena.k"}}, 7]' }]
  end
  expect("nothing personal left") do |events|
    text = events.map { |e| e.get("[event][original]") || e.get("message") }.join(" ")
    %w[artem.demo olena.k olena@example.com Olena Lena s3cret 3223130000026007233566 3999990000000000000000]
      .none? { |value| text.include?(value) }
  end
  expect("still JSON, with pseudonyms in their places") do |events|
    line = JSON.parse(events.first.get("[event][original]"))
    [line["user.name"], line["user"]["name"], line["user"]["email"], line["payment"]["creditorName"]]
      .all? { |value| value =~ /\Amasked-[0-9a-f]{12}\z/ } && line["db"]["password"] == "masked" &&
      line["message"] =~ /username="masked-[0-9a-f]{12}"; Detail: Key \(iban\)=\(masked\) already exists\.\z/ &&
      line.key?("UA***********************0002")
  end
  expect("the fields after a failing row kept") do |events|
    line = JSON.parse(events.first.get("[event][original]"))
    line["error.message"] == "Detail: Failing row contains (masked)." &&
      line["error.stack_trace"] == "boom\n\tat a.B.c(B.java:10)"
  end
  expect("an array is a document too") do |events|
    events[1].get("message") =~ /\A\[\{"user":\{"name":"masked-[0-9a-f]{12}"\}\},7\]\z/
  end
end

test "JSON with nothing to mask stays as it was written" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    { "message" => '{ "service.name": "bank-core", "status": 200 }', "tags" => "[not json",
      "error" => { "message" => '{"detail":"Failing row contains (masked).","trace":"at a.B.c(B.java:10)"}' } }
  end
  expect("unchanged") do |events|
    event = events.first
    event.get("message") == '{ "service.name": "bank-core", "status": 200 }' && event.get("tags") == "[not json" &&
      event.get("[error][message]") == '{"detail":"Failing row contains (masked).","trace":"at a.B.c(B.java:10)"}'
  end
end

test "JSON text inside text: escaped quotes and dotted keys" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event do
    [{ "message" => 'body={\"user.name\":\"artem.demo\",\"creditor_name\":\"Olena\",\"password\":\"s3cret\"} sent' },
     { "message" => 'refused for "user.name": "olena.k", "debtorName":"Artem Tkachuk"' },
     { "message" => 'retry username=\"artem.demo\" later' }, { "message" => "{not json} retry user_name=olena.k" }]
  end
  expect("none left") do |events|
    text = events.map { |e| e.get("message") }.join(" ")
    %w[artem.demo Olena s3cret olena.k Tkachuk].none? { |value| text.include?(value) }
  end
  expect("the escaping kept, each a pseudonym in its place") do |events|
    pseudonym = "masked-[0-9a-f]{12}"
    events[0].get("message") =~ /\Abody=\{\\"user\.name\\":\\"#{pseudonym}\\",\\"creditor_name\\":\\"#{pseudonym}/ &&
      events[0].get("message").end_with?('\"password\":\"masked\"} sent') &&
      events[1].get("message") =~ /\Arefused for "user\.name": "#{pseudonym}", "debtorName":"#{pseudonym}"\z/ &&
      events[2].get("message") =~ /\Aretry username=\\"#{pseudonym}\\" later\z/ &&
      events[3].get("message") =~ /\A\{not json\} retry user_name=#{pseudonym}\z/
  end
end

test "values that are not text stay as they are" do
  parameters { { "pseudonym_key" => "test-key-0123456789abcdef0123456789" } }
  in_event { { "process.pid" => 1, "event.duration" => 2.5, "retry" => true } }
  expect("unchanged") do |events|
    event = events.first
    event.get("[process.pid]") == 1 && event.get("[event.duration]") == 2.5 && event.get("retry") == true
  end
end
