import urllib.request
import os

url = "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0@aar"
# Wait, vosk-android is on Maven Central. Let's find the exact version.
import json

search_url = "https://search.maven.org/solrsearch/select?q=g:com.alphacep+a:vosk-android&rows=1&wt=json"
req = urllib.request.Request(search_url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode())
        version = data['response']['docs'][0]['latestVersion']
        print("Latest Vosk version:", version)
except Exception as e:
    print("Error:", e)
