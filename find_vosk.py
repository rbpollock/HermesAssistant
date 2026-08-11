import urllib.request, json
try:
    with urllib.request.urlopen("https://search.maven.org/solrsearch/select?q=g:com.alphacephei+a:vosk-android&rows=10&wt=json") as response:
        data = json.loads(response.read().decode())
        for doc in data['response']['docs']:
            print(doc['g'], doc['a'], doc['latestVersion'])
except Exception as e:
    print(e)
