import pandas as pd
from sklearn.ensemble import RandomForestClassifier
import sys

# load data
data = pd.read_csv("data.csv")

X = data[["requests"]]
y = data["label"]

# train model
model = RandomForestClassifier()
model.fit(X, y)

# input from Java
req = int(sys.argv[1])

# predict
prediction = model.predict([[req]])

print(prediction[0])
