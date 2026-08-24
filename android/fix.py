import os
filepath = r'F:\ibm hackathon\android\app\src\main\res\layout\activity_main.xml'
with open(filepath, 'r') as f:
    content = f.read()
new_content = content.replace('& Act', '& Act')
with open(filepath, 'w') as f:
    f.write(new_content)
print('Fixed')